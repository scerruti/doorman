#!/bin/bash

# Resolve the directory where this script lives
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
FAKE_DATA_DIR="$SCRIPT_DIR/fake_data"

prompt() {
    # Portable prompt function for bash, zsh, and sh
    if [ -n "$ZSH_VERSION" ]; then
        read "REPLY?$1"
    else
        read -p "$1" REPLY
    fi
}

print_exports() {
    export BLE_BACKEND="$1"
    if [[ -n "$2" ]]; then
        export BLE_FAKE_DATA="$2"
    else
        unset BLE_FAKE_DATA
    fi
}

set_scenario() {
    case "$1" in
        real)
            print_exports "real"
            ;;
        none)
            print_exports "fake" "$FAKE_DATA_DIR/fake_none.json"
            ;;
        one)
            print_exports "fake" "$FAKE_DATA_DIR/fake_one.json"
            ;;
        two)
            print_exports "fake" "$FAKE_DATA_DIR/fake_two.json"
            ;;
        *)
            echo "Unknown scenario: $1"
            return 1
            ;;
    esac
}

menu() {
    echo "Select BLE backend:"
    echo "1. Real BLE"
    echo "2. Fake BLE (no SwitchBot)"
    echo "3. Fake BLE (one SwitchBot)"
    echo "4. Fake BLE (two SwitchBots)"

    prompt "Enter choice: "

    case "$REPLY" in
        1) set_scenario real ;;
        2) set_scenario none ;;
        3) set_scenario one ;;
        4) set_scenario two ;;
        *) echo "Invalid choice" ;;
    esac
}

show_env() {
    echo "BLE_BACKEND=${BLE_BACKEND:-unset}"
    echo "BLE_FAKE_DATA=${BLE_FAKE_DATA:-unset}"
}

clear_env() {
    unset BLE_BACKEND
    unset BLE_FAKE_DATA
    echo "BLE environment cleared"
}

# Command-line mode
if [[ "$1" == "--set" && -n "$2" ]]; then
    set_scenario "$2"
elif [[ "$1" == "--show" ]]; then
    show_env
elif [[ "$1" == "--clear" ]]; then
    clear_env
else
    menu
fi

show_env
