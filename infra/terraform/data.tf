resource "aws_db_subnet_group" "main" {
  name_prefix = "mv-"
  subnet_ids  = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier                  = "malhaviva-${var.env}"
  engine                      = "postgres"
  engine_version              = "17"
  instance_class              = var.db_instance_class
  allocated_storage           = 50
  max_allocated_storage       = 200
  storage_encrypted           = true
  db_name                     = "malhaviva"
  username                    = "malhaviva"
  manage_master_user_password = true
  db_subnet_group_name        = aws_db_subnet_group.main.name
  vpc_security_group_ids      = [aws_security_group.data.id]
  multi_az                    = true
  backup_retention_period     = 7
  deletion_protection         = true
  skip_final_snapshot         = false
  final_snapshot_identifier   = "malhaviva-${var.env}-final"
}

resource "aws_elasticache_subnet_group" "main" {
  name       = "malhaviva-${var.env}"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_replication_group" "cache" {
  replication_group_id       = "malhaviva-${var.env}"
  description                = "Malha Viva cache"
  engine                     = "valkey"
  engine_version             = "8.0"
  node_type                  = var.cache_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.data.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
}

resource "aws_secretsmanager_secret" "app" {
  for_each = toset(["jwt-signing-key", "password-pepper"])
  name     = "malhaviva/${var.env}/${each.key}"
}
