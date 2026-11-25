variable "region" {
  type    = string
  default = "sa-east-1"
}

variable "env" {
  type    = string
  default = "prod"
}

variable "certificate_arn" {
  description = "ACM certificate (sa-east-1) for the HTTPS listener."
  type        = string
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "cache_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "desired_count" {
  type    = number
  default = 2
}
