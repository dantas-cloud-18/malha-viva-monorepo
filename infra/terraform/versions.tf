terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.74"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      project = "malha-viva"
      env     = var.env
    }
  }
}
