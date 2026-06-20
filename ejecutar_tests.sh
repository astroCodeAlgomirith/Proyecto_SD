#!/usr/bin/env bash
# Corre el harness de pruebas en Java puro (sin JUnit).
# mvn package compila src/test/java a target/test-classes; luego se corre el runner.
set -e
mvn -q package
java -cp "target/banco.jar:target/test-classes" pruebas.TestRunner
