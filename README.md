# Accounting System

Java Swing desktop app with a tamper-evident ledger, health score and smart dues collector.

## Run (needs Java 11+ installed)
1. Download the zip from the Releases page and unzip it.
2. Double-click run.bat.

## Build from source
javac -d out -cp "lib\*" (Get-ChildItem -Recurse src -Filter *.java).FullName
java -cp "out;lib\*" com.accounting.ui.AppMain
