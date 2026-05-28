FROM php:8.3-cli

WORKDIR /app

# Install PHP extensions required by the Global Payments SDK
RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip wget libcurl4-openssl-dev libicu-dev && \
    docker-php-ext-install curl intl && \
    rm -rf /var/lib/apt/lists/*

# Install Composer
COPY --from=composer:latest /usr/bin/composer /usr/bin/composer

# Copy composer files and install locked dependencies
COPY composer.json composer.lock ./
RUN composer install --no-dev --optimize-autoloader --no-interaction

# Copy source code
COPY . .

EXPOSE 8080

CMD ["php", "-S", "0.0.0.0:8080", "router.php"]
