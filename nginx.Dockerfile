FROM nginx:alpine

# Copy nginx configuration
COPY nginx.conf /etc/nginx/nginx.conf

# Copy entrypoint to generate htpasswd at runtime
COPY nginx-entrypoint.sh /usr/local/bin/nginx-entrypoint.sh
RUN chmod +x /usr/local/bin/nginx-entrypoint.sh

# Expose port 80 for the reverse proxy
EXPOSE 80

ENTRYPOINT ["/usr/local/bin/nginx-entrypoint.sh"]
# Start nginx
CMD ["nginx", "-g", "daemon off;"]
