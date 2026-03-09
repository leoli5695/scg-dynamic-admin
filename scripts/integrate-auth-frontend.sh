#!/bin/bash

# 网关鉴权模块前端自动集成脚本
# 自动将 Auth 配置表单和 JavaScript 添加到 index.html

set -e

echo "🚀 Starting frontend integration for Auth module..."

# Define paths
GATEWAY_ADMIN_DIR="d:/source/gateway-admin/src/main/resources/templates"
INDEX_HTML="$GATEWAY_ADMIN_DIR/index.html"
AUTH_FORM_TEMPLATE="$GATEWAY_ADMIN_DIR/index-auth-form.html"
AUTH_JS_TEMPLATE="$GATEWAY_ADMIN_DIR/index-auth-js.html"

# Check if files exist
if [ ! -f "$INDEX_HTML" ]; then
    echo "❌ Error: index.html not found at $INDEX_HTML"
    exit 1
fi

if [ ! -f "$AUTH_FORM_TEMPLATE" ]; then
    echo "❌ Error: index-auth-form.html not found"
    exit 1
fi

if [ ! -f "$AUTH_JS_TEMPLATE" ]; then
    echo "❌ Error: index-auth-js.html not found"
    exit 1
fi

echo "✅ All required files found"

# Create backup
BACKUP_FILE="$INDEX_HTML.backup.$(date +%Y%m%d_%H%M%S)"
cp "$INDEX_HTML" "$BACKUP_FILE"
echo "✅ Backup created: $BACKUP_FILE"

# Extract content from template files
AUTH_FORM_CONTENT=$(cat "$AUTH_FORM_TEMPLATE")
AUTH_JS_CONTENT=$(cat "$AUTH_JS_TEMPLATE")

# Find the position to insert HTML (before </div><!-- End of Plugins Panel -->)
if grep -q "<!-- End of Plugins Panel -->" "$INDEX_HTML"; then
    echo "✅ Found insertion point for HTML"
    
    # Insert HTML form before Timeout Plugin section end
    # Use sed to insert before the closing div comment
    sed -i "/<!-- End of Plugins Panel -->/i \\$AUTH_FORM_CONTENT" "$INDEX_HTML"
    echo "✅ HTML form inserted successfully"
else
    echo "⚠️  Could not find '<!-- End of Plugins Panel -->', trying alternative method..."
    
    # Alternative: Find the last </div> in plugins panel
    # This is more complex, so we'll use a simpler approach
    echo "Please manually add the auth form HTML to index.html"
fi

# Find the position to insert JS (before </script> tag)
if grep -q "</script>" "$INDEX_HTML"; then
    echo "✅ Found insertion point for JavaScript"
    
    # Insert JS before closing script tag
    sed -i "/<\/script>/i \\$AUTH_JS_CONTENT" "$INDEX_HTML"
    echo "✅ JavaScript inserted successfully"
else
    echo "❌ Could not find closing </script> tag"
    echo "Please manually add the JavaScript code to index.html"
fi

echo ""
echo "=========================================="
echo "✅ Frontend integration completed!"
echo "=========================================="
echo ""
echo "Backup saved as: $BACKUP_FILE"
echo ""
echo "Next steps:"
echo "1. Open $INDEX_HTML in your editor"
echo "2. Verify the HTML form was added correctly"
echo "3. Verify the JavaScript was added correctly"
echo "4. Test the auth configuration UI"
echo ""
echo "If you encounter any issues, restore from backup:"
echo "  cp $BACKUP_FILE $INDEX_HTML"
echo ""
