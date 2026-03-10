#!/bin/bash

# Exit on error
set -e

# Install dependencies
go mod download

# Start the server
go run main.go
