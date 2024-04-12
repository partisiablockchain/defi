#!/usr/bin/env bash

RED='\033[0;31m'
NOCOLOR='\033[0m'

REGEX_CONTRACT='/(([0-9]|[a-f]){42})'
REGEX_TRANSACTION_ADDRESS='transactions/(([0-9]|[a-f]){24})(([0-9]|[a-f]){40})'

CONTRACT_DIR="rust/target/wasm32-unknown-unknown/release/"

# Deploy an amount of token contracts with the same total supply and decimals used.
# Populates a given array with the contract addresses of the deployed contracts.
# $1 - path to private key.
# $2 - how many token contracts to deploy.
# $3 - decimals for each token.
# $4 - total supply for each token.
# $5 - token address array to populate.
function deploy_token_contracts() {
  local -n loc_token_addresses=$5
  local alphabet=({A..Z})

  for ((i = 0; i < $2; i++)); do
    local token_name="Demo Token ${alphabet[i]}"
    local token_symbol="DT${alphabet[i]}"
    local output_text
    output_text=$(cargo partisia-contract transaction deploy \
      --privatekey "$1" \
      --gas 1100000 \
      "$CONTRACT_DIR"/token.wasm \
      "$CONTRACT_DIR"/token.abi \
      "$token_name" "$token_symbol" "$3" "$4")
    [[ $output_text =~ $REGEX_CONTRACT ]] && loc_token_addresses+=("${BASH_REMATCH[1]}")
  done
}

# Deploys a DEX Swap factory, used to deploy swap contracts between tokens.
# $1 - path to private key.
# $2 - permission of updating swap binary.
# $3 - permission of deploying new swap.
# $4 - fee in per mille.
function deploy_factory() {
  local output_text
  output_text=$(cargo partisia-contract transaction deploy \
    --privatekey "$1" \
    --gas 1300000 \
    "$CONTRACT_DIR"/dex_swap_factory.wasm \
    "$CONTRACT_DIR"/dex_swap_factory.abi \
    Specific "{" [ "$2" ] "}" \
    Specific "{" [ "$3" ] "}" \
    "$4")

  [[ $output_text =~ $REGEX_CONTRACT ]] && echo "${BASH_REMATCH[1]}"
}

# Deploys a new swap contract (with locks supported), between two tokens,
# through a DEX factory contract.
# $1 - path to private key.
# $2 - dex factory address.
# $3 - first swap token.
# $4 - second swap token.
# $5 - Address of contract permitted to acquired locks.
function deploy_swap_through_factory() {
  local output_text
  output_text=$(cargo partisia-contract transaction action \
    --privatekey "$1" \
    --gas 1100000 \
    "$2" \
    deploy_swap_lock_contract \
    "{" "$3" "$4" "}" \
    Specific "{" [ "$5" ] "}")

  [[ $output_text =~ $REGEX_TRANSACTION_ADDRESS ]] && echo "02${BASH_REMATCH[3]}"
}

# Update the swap binary at the DEX factory, to be able to deploy swap contracts.
# $1 - path to private key.
# $2 - dex factory address.
function update_swap_binary() {
  cargo partisia-contract transaction action \
    --privatekey "$1" \
    --gas 1000000 \
    "$2" \
    update_swap_binary \
    file:"$CONTRACT_DIR"/liquidity_swap_lock.wasm \
    file:"$CONTRACT_DIR"/liquidity_swap_lock.abi \
    4511
}

# Deploys a swap router, to be able to route swaps between deployed swap contracts.
# $1 - path to private key.
# $2 - permission for adding swap contracts.
function deploy_router() {
  local output_text
  output_text=$(cargo partisia-contract transaction deploy \
    --privatekey "$1" \
    --gas 2000000 \
    "$CONTRACT_DIR"/swap_router.wasm \
    "$CONTRACT_DIR"/swap_router.abi \
    Specific "{" [ "$2" ] "}" \
    [ ])

  [[ $output_text =~ $REGEX_CONTRACT ]] && echo "${BASH_REMATCH[1]}"
}

# Adds a deployed swap contract to a router, so the router is able
# to use the swap contract for routing.
# $1 - path to private key.
# $2 - address of the routing contract.
# $3 - address of the swap contract to add.
# $4 - address of the 'a' token of the swap address.
# $5 - address of the 'b' token of the swap address.
function add_swap_to_router() {
  cargo partisia-contract transaction action \
    --privatekey "$1" \
    --gas 3000 \
    "$2" \
    add_swap_contract \
    "$3" \
    "$4" \
    "$5"
}

# Approves an amount at a token, to allow other contracts to spend
# tokens on the senders behalf. Used before deposits and route swaps.
# $1 - privatekey path.
# $2 - Address of token to approve at.
# $3 - Address to approve.
# $4 - Approval amount.
function approve() {
  cargo partisia-contract transaction action \
    --privatekey "$1" \
    --gas 20000 \
    "$2" \
    approve \
    "$3" \
    "$4"
}

# Used to deposit tokens into a swap contract.
# $1 - privatekey path.
# $2 - Swap address to deposit at.
# $3 - Address of token to deposit.
# $4 - Amount to deposit.
function deposit() {
  cargo partisia-contract transaction action \
    --privatekey "$1" \
    --gas 20000 \
    "$2" \
    deposit \
    "$3" \
    "$4"
}

# Used to provide the initial liquidity at a swap contract,
# with previously deposited tokens.
# $1 - path to private key.
# $2 - swap contract address.
# $3 - first token liquidity.
# $4 - second token liquidity.
function provide_initial_liquidity() {
  cargo partisia-contract transaction action \
    --privatekey "$1" \
    --gas 3000 \
    "$2" \
    provide_initial_liquidity \
    "$3" \
    "$4"
}

# Performs the approval and deposit of tokens into a swap contract.
# $1 - privatekey path.
# $2 - Address of swap contract.
# $3 - Address of token.
# $4 - Approval / deposit amount.
function approve_and_deposit_into_swap() {
  approve "$1" "$3" "$2" "$4"
  deposit "$1" "$2" "$3" "$4"
}

# Routes a swap between two tokens, using any swap contracts
# which are known to the routing contract.
# $1 - privatekey path.
# $2 - Address of router.
# $3 - Starting token.
# $4 - Ending token.
# $5 - Input amount.
# $6 - Minimum out.
# $7 - Route address array
function route_swap() {
  private_key="$1"
  address_router="$2"
  address_start="$3"
  address_end="$4"
  input_amount="$5"
  minimum_out="$6"

  shift 6

  # Approve the router at the starting token
  approve "$private_key" "$address_start" "$address_router" "$input_amount"

  # Route swap
  cargo partisia-contract transaction action \
    --privatekey "$private_key" \
    --gas 500000 \
    "$address_router" \
    route_swap \
    [ "$@" ] \
    "$address_start" \
    "$address_end" \
    "$input_amount" \
    "$minimum_out"
}

function deploy_help() {
  echo "Usage: $0 deploy [-f <swap-fee-per-mille>] -p <file> -a <address>
Deploy a small constellation of token contracts, swap contracts and a routing contract.
Currently creates 3 tokens: A, B, C, a dex-swap factory, 2 swap contracts through the factory: AB, BC, and a router.
  -p <file>               <file> must be a path to the private key of the sender account
  -f <swap-fee-per-mille> Swap fee of the deployed swap contracts.
  -a <address>            Address of the account associated with the private key used with -p.
                          This is the account who has permission to update swap binaries, deploy new swaps and update known swaps at the router."
  exit "${1:-0}"
}

# Setup a small constellation of token, factory, swap and routing contracts,
# with provided liquidity at swap contracts,
# to be able to more easily experiment with routing swaps.
# Addresses of all deployed contracts are stored in a local file 'state.var'.
function deploy() {
  if [[ ${#} == 0 ]]; then
    deploy_help 0
  fi

  local __privatekey_path=""

  local __swap_fee_per_mille=3

  local __update_swap_binary_account_permission=""
  local __deploy_swap_contract_account_permission=""

  local __update_router_known_swaps_account_permission=""

  while [ ${#} -gt 0 ]; do
    error_message="Error: a value is needed for '$1'"
    case $1 in
      -p | --private-key)
        __privatekey_path=${2:?$error_message}
        shift 2
        ;;
      -f | --factory)
        local __swap_fee_per_mille=${2:?error_message}
        shift 2
        ;;
      -a | --account-address)
        __update_swap_binary_account_permission=${2:?$error_message}
        __deploy_swap_contract_account_permission=${2:?$error_message}
        __update_router_known_swaps_account_permission=${2:?$error_message}
        shift 2
        ;;
      *)
        echo -e "${RED}unknown option $1${NOCOLOR}"
        exit 1
        ;;
    esac
  done

  if [[ -z "${__privatekey_path}" ]]; then
    echo -e "${RED}ERROR: Must provide a private key${NOCOLOR}"
    deploy_help 1
  fi

  if [[ -z "${__update_swap_binary_account_permission}" ]]; then
    echo -e "${RED}ERROR: Must provide account address${NOCOLOR}"
    deploy_help 1
  fi

  local __token_decimals=4
  local __token_supply="100000000000"
  local token_addresses=()
  deploy_token_contracts "$__privatekey_path" "3" "$__token_decimals" "$__token_supply" token_addresses

  local token_a=${token_addresses[0]}
  local token_b=${token_addresses[1]}
  local token_c=${token_addresses[2]}

  local dex_address
  dex_address=$(deploy_factory "$__privatekey_path" "$__update_swap_binary_account_permission" "$__deploy_swap_contract_account_permission" "$__swap_fee_per_mille")

  update_swap_binary "$__privatekey_path" "$dex_address"

  local routing_address
  routing_address=$(deploy_router "$__privatekey_path" "$__update_router_known_swaps_account_permission")

  # Creates the following constellation of swaps:
  #
  #      A<-->B<-->C
  #

  local swap_ab
  swap_ab=$(deploy_swap_through_factory "$__privatekey_path" "$dex_address" "$token_a" "$token_b" "$routing_address") # A-B
  local swap_bc
  swap_bc=$(deploy_swap_through_factory "$__privatekey_path" "$dex_address" "$token_b" "$token_c" "$routing_address") # B-C

  # Update the router, so it knows the swap contracts.
  add_swap_to_router "$__privatekey_path" "$routing_address" "$swap_ab" "$token_a" "$token_b"
  add_swap_to_router "$__privatekey_path" "$routing_address" "$swap_bc" "$token_b" "$token_c"

  # Setup swap contracts with liquidity:
  # Approve swaps to deposit
  approve "$__privatekey_path" "$token_a" "$swap_ab" "10000000000"
  approve "$__privatekey_path" "$token_b" "$swap_ab" "10000000000"
  approve "$__privatekey_path" "$token_b" "$swap_bc" "10000000000"
  approve "$__privatekey_path" "$token_c" "$swap_bc" "10000000000"

  # deposit into swaps
  deposit "$__privatekey_path" "$swap_ab" "$token_a" "10000000000"
  deposit "$__privatekey_path" "$swap_ab" "$token_b" "10000000000"
  deposit "$__privatekey_path" "$swap_bc" "$token_b" "10000000000"
  deposit "$__privatekey_path" "$swap_bc" "$token_c" "10000000000"

  # provide the initial liquidity
  provide_initial_liquidity "$__privatekey_path" "$swap_ab" "10000000000" "10000000000"
  provide_initial_liquidity "$__privatekey_path" "$swap_bc" "10000000000" "10000000000"

  echo "Contracts have been deployed at the following addresses:
Token A: $token_a
Token B: $token_b
Token C: $token_c

Swap factory: $dex_address

Swap AB: $swap_ab
Swap BC: $swap_bc

Router: $routing_address
"

  echo "TOKEN_A=$token_a
TOKEN_B=$token_b
TOKEN_C=$token_c
FACTORY=$dex_address
SWAP_AB=$swap_ab
SWAP_BC=$swap_bc
ROUTER=$routing_address
" > scripts/state.var
}

function route_help() {
  echo "Usage: $0 route -p <file> <start-token> <end-token> <input-amount> <minimum-out> <swap-route>
Route a swap starting from <input-amount> of <start-token> to at least <minimum-out> of <end-token> using the specific <swap-route>.
A 'state.var' file must be present which stores the addresses of the token, swap and router contracts.
  -p <file>       <file> must be a path to the private key of the sender account.
  <start-token>   Name of token to start with.
                  The name prefixed with 'TOKEN_' must be stored in state.var (e.g. TOKEN_A=<address> stored in state.var, then use A as the name).
  <end-token>     Name of token to end with.
                  The name prefixed with 'TOKEN_' must be stored in state.var (like <start-token>)
  <input-amount>  How many <start-token> to start the swap route with.
  <minimum-out>   How many <end-token> are minimally required to be output by the end.
  <swap-route>    A list of the swap contract names to use, in order. (e.g. AB BC)
                  A list of addreses prefixed with 'SWAP_' must be stored in state.var, e.g. SWAP_AB=<address>"
  exit "${1:-0}"
}

# Routes a swap between two named tokens using the provided named swap contracts.
# Addresses of the contracts used are read from 'state.var' file.
function route() {
  if [[ ${#} == 0 ]]; then
    route_help 0
  fi

  local __privatekey_path=""

  error_message="Error: a value is needed for '$1'"
  case $1 in
    -p | --private-key)
      __privatekey_path=${2:?$error_message}
      shift 2
      ;;
    *)
      echo -e "${RED}Unknown option $1${NOCOLOR}"
      exit 1
      ;;
  esac

  if [[ -z "${__privatekey_path}" ]]; then
    echo -e "${RED}Must provide a private key${NOCOLOR}"
    route_help 1
  fi

  local __start_token="TOKEN_$1"
  local __end_token="TOKEN_$2"

  local __input_amount="$3"
  local __minimum_out="$4"

  shift 4

  FILE=scripts/state.var
  if [ -f "$FILE" ]; then
    # shellcheck source=state.var
    # shellcheck disable=SC1091
    . "$FILE"

    local conv_arr=()
    for v in "$@"; do
      local tmp="SWAP_$v"
      conv_arr+=("${!tmp}")
    done

    route_swap "$__privatekey_path" "$ROUTER" "${!__start_token}" "${!__end_token}" "$__input_amount" "$__minimum_out" "${conv_arr[@]}"
  else
    echo "No state.var file with contract addresses"
    exit 1
  fi
}

function help() {
  echo "
Helper script for setting up swap constellation and routing simple swaps.
Usage: $0 COMMAND
Commands:
  help    Prints this usage message
  deploy  Deploys 3 token contracts, a dex swap factory, 2 swap contracts and a router.
          Additionally provides initial liquidity for both swap contracts.
  route   Routes a swap between given tokens using the previously deployed router."
  exit "${1:-0}"
}

function main() {
  if ((${#} == 0)); then
    help 0
  fi

  case ${1} in
    help | deploy | route)
      $1 "${@:2}"
      ;;
    *)
      echo "unknown command: $1"
      help 1
      ;;
  esac
}

main "$@"
