@echo off
setlocal

set MORE_JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005
call %*