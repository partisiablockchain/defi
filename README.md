# DeFi Smart Contracts

The Partisia Blockchain Foundation provides the following reviewed smart contracts,
as examples of decentralized finance problems with a blockchain solution.

The Defi smart contracts repo is created as a collection of complex examples with a mature code base. If you are new to
writing smart contracts on Partisia
Blockchain we recommend you to
visit [our documentation](https://partisiablockchain.gitlab.io/documentation/smart-contracts/introduction-to-smart-contracts.html)
and [the example contracts](https://gitlab.com/partisiablockchain/language/example-contracts).

This repository contains multiple example smart contracts as a virtual cargo workspace.
To compile all the contracts using the partisia-contract tool run:

    cargo partisia-contract build --release

To compile a single contract change directory to the specific contract and run the same command.
For example:

    cd nft
    cargo partisia-contract build --release

The compiled wasm/zkwa and abi files are located in

    target/wasm32-unknown-unknown/release

To run the test suite, run the following command:

    ./run-java-tests.sh

To generate the code coverage report, run the following command:

    cargo partisia-contract build --coverage

The coverage report will be located in java-test/target/coverage
