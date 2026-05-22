FROM php:8.3-cli

WORKDIR /app

# Install cURL extension
RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip wget libcurl4-openssl-dev && \
    docker-php-ext-install curl && \
    rm -rf /var/lib/apt/lists/*

# Install Composer
COPY --from=composer:latest /usr/bin/composer /usr/bin/composer

# Copy composer files and install dependencies (no lock file needed)
COPY composer.json ./
RUN composer install --no-dev --optimize-autoloader --no-interaction

# Copy source code
COPY . .

EXPOSE 8080

CMD ["php", "-S", "0.0.0.0:8080", "router.php"]
