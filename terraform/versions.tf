terraform {
  required_version = ">= 1.5"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
  }

  # State cuc bo (terraform.tfstate, da gitignore). Lam nhom thi chuyen sang Azure Storage:
  # backend "azurerm" {
  #   resource_group_name  = "rg-tfstate"
  #   storage_account_name = "<ten-storage>"
  #   container_name       = "tfstate"
  #   key                  = "ftpserver.tfstate"
  # }
}

provider "azurerm" {
  features {}
  # Dang nhap bang `az login`; subscription lay tu bien hoac ARM_SUBSCRIPTION_ID
  subscription_id = var.subscription_id
}
