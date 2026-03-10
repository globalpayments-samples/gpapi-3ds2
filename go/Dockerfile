FROM golang:1.23-alpine AS builder

WORKDIR /app

# Copy go mod files and download dependencies
COPY go.mod go.sum ./
RUN go mod download

# Copy source code and build
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o main .

# Runtime stage
FROM alpine:latest

WORKDIR /app

# Install ca-certificates for HTTPS calls
RUN apk --no-cache add ca-certificates

# Copy the binary from builder stage
COPY --from=builder /app/main .
COPY --from=builder /app/static ./static

# Create non-root user
RUN addgroup -g 1001 -S appuser && \
    adduser -S appuser -u 1001
USER appuser

EXPOSE 8000

CMD ["./main"]