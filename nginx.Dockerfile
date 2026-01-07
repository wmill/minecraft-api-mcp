FROM nginx:alpine

# Copy nginx configuration
COPY nginx.conf /etc/nginx/nginx.conf

# Expose port 80 for the reverse proxy
EXPOSE 80

# Start nginx
CMD ["nginx", "-g", "daemon off;"]