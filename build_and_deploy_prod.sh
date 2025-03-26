#!/usr/bin/env bash
set -e

docker login -u AWS -p $(aws ecr get-login-password --region us-west-2) 114512187555.dkr.ecr.us-west-2.amazonaws.com

SERVICE=dart/kafka-connect-snowflake
BASE_IMAGE=114512187555.dkr.ecr.us-west-2.amazonaws.com/$SERVICE
TAG=3.1.2
APP_ENV=prod

docker buildx build \
  --platform linux/arm64,linux/amd64 \
  --tag $BASE_IMAGE:$TAG \
  --build-arg "APP_ENV=$APP_ENV" \
  --push \
  .