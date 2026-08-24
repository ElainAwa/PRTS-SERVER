@echo off
javac ClassDumpAgent.java
jar cfm ClassDumpAgent.jar MANIFEST.MF ClassDumpAgent.class "ClassDumpAgent$1.class"
