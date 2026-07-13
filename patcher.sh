#!/bin/bash

# Quick patcher script (Paper-style per-file patches)
# Usage: ./patcher.sh <command> <args?>

set -e

# Find gradle
if [ -f "./gradlew" ]; then
    GRADLE="./gradlew"
elif command -v gradle &> /dev/null; then
    GRADLE="gradle"
else
    echo "Error: Can't find gradle. Run this from the project root."
    exit 1
fi

case "$1" in
    setup|init)
        # Decompile (if needed) + distribute base + apply all file patches.
        echo "=== Setup ==="
        $GRADLE setup
        echo "Done!"
        ;;

    fresh)
        echo "=== Fresh Start ==="
        echo "This wipes module sources + the decompiled base and rebuilds from scratch."
        read -p "Continue? [y/N] " confirm
        if [[ "$confirm" =~ ^[Yy]$ ]]; then
            $GRADLE cleanDistributedSources cleanGenerated cleanCache
            $GRADLE setup
            echo "Finished!"
        fi
        ;;

    status|s)
        # Which module files differ from the pristine base.
        $GRADLE patchStatus
        ;;

    apply|a)
        # Reconstruct the working tree: base + every file patch.
        $GRADLE applyPatches
        ;;

    apply-only)
        # Overlay patches onto whatever is already in the modules (no re-distribute).
        $GRADLE applyFilePatches
        ;;

    apply-fuzzy|af)
        # Same as apply but adds a fuzzy `patch` rung before rejecting hunks.
        $GRADLE applyFilePatchesFuzzy
        ;;

    rebuild|r)
        # Regenerate per-file patches from your current module edits.
        # This is also how you "finish" a conflict: fix the files, then rebuild.
        $GRADLE rebuildFilePatches
        ;;

    reset)
        # Throw away manual module edits, rebuild working tree from base + patches.
        echo "This discards uncommitted edits in the module sources."
        read -p "Continue? [y/N] " confirm
        if [[ "$confirm" =~ ^[Yy]$ ]]; then
            $GRADLE resetSources
        fi
        ;;

    list|l)
        $GRADLE listPatches
        ;;

    inspect|i)
        $GRADLE inspectDecompiledStructure
        ;;

    clean)
        echo "Clean options:"
        echo "  1) Module sources"
        echo "  2) Generated/decompiled base"
        echo "  3) Patch work dirs (.patch-work / .patch-rejects)"
        echo "  4) All"
        read -p "Choice: " choice
        case $choice in
            1) $GRADLE cleanDistributedSources ;;
            2) $GRADLE cleanGenerated ;;
            3) $GRADLE cleanCache ;;
            4) $GRADLE cleanDistributedSources cleanGenerated cleanCache ;;
        esac
        ;;

    *)
        echo "Patcher commands:"
        echo ""
        echo "  setup, init      - Decompile + distribute base + apply all file patches"
        echo "  fresh            - Wipe everything and rebuild from scratch"
        echo ""
        echo "  status, s        - Show which module files differ from the base"
        echo "  apply, a         - Reconstruct module sources = base + all patches"
        echo "  apply-fuzzy, af  - apply with an extra fuzzy matching rung"
        echo "  apply-only       - Overlay patches without re-distributing the base"
        echo "  rebuild, r       - Regenerate per-file patches from current module edits"
        echo "  reset            - Discard module edits; rebuild tree from base + patches"
        echo "  list, l          - List the per-file patches"
        echo ""
        echo "  inspect, i       - Show decompiled structure"
        echo "  clean            - Clean up sources / base / work dirs"
        echo ""
        echo "Conflict workflow (one patch per file, so failures are isolated):"
        echo "  1) './patcher.sh apply' reports files needing attention"
        echo "       - conflict markers are left directly in the module file"
        echo "       - rejected hunks are written to .patch-rejects/<path>.rej"
        echo "  2) Fix those files in your IDE"
        echo "  3) './patcher.sh rebuild' regenerates clean patches from your fixes"
        echo ""
        echo "Edit / add code:"
        echo "  1) Edit files in the module src tree (or add new ones)"
        echo "  2) './patcher.sh status' to see what changed"
        echo "  3) './patcher.sh rebuild' to update the patch tree"
        echo ""
        echo "Examples:"
        echo "  ./patcher.sh setup"
        echo "  ./patcher.sh status"
        echo "  ./patcher.sh rebuild"
        ;;
esac