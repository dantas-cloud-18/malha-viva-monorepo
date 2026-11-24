resource "aws_ecr_repository" "backend" {
  name                 = "malhaviva-backend"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/malhaviva/${var.env}/backend"
  retention_in_days = 30
}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name_prefix        = "mv-exec-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "read_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = concat(
      [aws_db_instance.main.master_user_secret[0].secret_arn],
      [for s in aws_secretsmanager_secret.app : s.arn],
    )
  }
}

resource "aws_iam_role_policy" "read_secrets" {
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.read_secrets.json
}

resource "aws_iam_role" "task" {
  name_prefix        = "mv-task-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_ecs_cluster" "main" {
  name = "malhaviva-${var.env}"
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "malhaviva-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions = jsonencode([{
    name         = "backend"
    image        = "${aws_ecr_repository.backend.repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8080 }]
    environment = [
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.endpoint}/malhaviva" },
      { name = "DB_USER", value = "malhaviva" },
      { name = "REDIS_URL", value = "rediss://${aws_elasticache_replication_group.cache.primary_endpoint_address}:6379" },
      { name = "JWT_ISSUER", value = "malha-viva" },
      { name = "JWT_AUDIENCE", value = "malha-viva-app" },
    ]
    secrets = [
      { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
      { name = "JWT_SIGNING_KEY", valueFrom = aws_secretsmanager_secret.app["jwt-signing-key"].arn },
      { name = "PASSWORD_PEPPER", valueFrom = aws_secretsmanager_secret.app["password-pepper"].arn },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.backend.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "backend"
      }
    }
  }])
}

resource "aws_lb" "main" {
  name_prefix        = "mv-"
  load_balancer_type = "application"
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]
}

resource "aws_lb_target_group" "backend" {
  name_prefix = "mv-"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id
  health_check {
    path = "/health"
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}

resource "aws_ecs_service" "backend" {
  name            = "backend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"
  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.app.id]
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = 8080
  }
  depends_on = [aws_lb_listener.https]
}
