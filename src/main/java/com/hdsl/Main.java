package com.hdsl;

/** Separate launcher entry point allows JavaFX to run from packaged classpath. */
public final class Main {
    public static void main(String[] args) { javafx.application.Application.launch(HdslApplication.class,args); }
}
