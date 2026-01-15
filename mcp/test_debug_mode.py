#!/usr/bin/env python3
"""
Test DEBUG mode with debugpy.
"""

import os
import sys

# Set DEBUG mode before importing config
os.environ['DEBUG'] = '1'

print("Testing DEBUG mode with DEBUG=1 environment variable...", file=sys.stderr)

# Now import and test
from minecraft_mcp import config

print(f"DEBUG mode is: {config.DEBUG}", file=sys.stderr)

# Call setup_debug_mode
config.setup_debug_mode()

print("✓ DEBUG mode test completed successfully", file=sys.stderr)
