#!/bin/bash
set -euo pipefail

# Usage: ./scripts/release.sh [version]
# Example: ./scripts/release.sh 1.0.0
# Example: ./scripts/release.sh           # auto-increments minor version
#
# Creates a release branch, pushes it, and creates a GitHub release
# which triggers the publish workflow to deploy to Maven Central.

if [ -n "${1:-}" ]; then
    VERSION="$1"
else
    # Auto-increment: find latest release tag, bump minor version
    LATEST=$(gh release list --repo notoriouscorgi/CacheOnHand --limit 1 --json tagName -q '.[0].tagName // empty' 2>/dev/null || true)

    if [ -z "$LATEST" ]; then
        VERSION="0.1.0"
        echo "No previous releases found. Starting at v${VERSION}"
    else
        # Strip leading 'v', split on dots
        LATEST="${LATEST#v}"
        IFS='.' read -r MAJOR MINOR PATCH <<< "$LATEST"
        MINOR=$((MINOR + 1))
        VERSION="${MAJOR}.${MINOR}.0"
        echo "Latest release: v${LATEST} → incrementing to v${VERSION}"
    fi
fi

# Validate semver format
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
    echo "Error: Version must be in semver format (e.g., 1.0.0 or 1.0.0-beta.1)"
    exit 1
fi

BRANCH="release/v${VERSION}"

echo "Creating release v${VERSION}..."

# Ensure we're on main and up to date
git checkout main
git pull origin main

# Create release branch
git checkout -b "$BRANCH"
git push -u origin "$BRANCH"

# Create GitHub release (triggers publish workflow)
gh release create "v${VERSION}" \
    --target "$BRANCH" \
    --title "v${VERSION}" \
    --generate-notes

echo ""
echo "Release v${VERSION} created!"
echo "  Branch: $BRANCH"
echo "  GitHub Release: https://github.com/notoriouscorgi/CacheOnHand/releases/tag/v${VERSION}"
echo ""
echo "The publish workflow will now build and deploy to Maven Central."
echo "Monitor progress: gh run list --repo notoriouscorgi/CacheOnHand"
