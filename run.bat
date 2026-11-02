@echo off
if not exist db mkdir db
java -cp "accounting.jar;lib\*" com.accounting.ui.AppMain
pause
