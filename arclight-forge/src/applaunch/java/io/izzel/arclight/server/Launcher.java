package io.izzel.arclight.server;

import io.izzel.arclight.boot.application.Main_Forge;

import java.io.*;

public class Launcher {

    private static final int MIN_CLASS_VERSION = 61;
    private static final int MIN_JAVA_VERSION = 17;
    private static final String EULA_URL = "https://account.mojang.com/documents/minecraft_eula";
    private static final String EULA_FILE = "eula.txt";

    public static void main(String[] args) throws Throwable {
        int javaVersion = (int) Float.parseFloat(System.getProperty("java.class.version"));
        if (javaVersion < MIN_CLASS_VERSION) {
            System.err.println("Arclight requires Java " + MIN_JAVA_VERSION);
            System.err.println("Current: " + System.getProperty("java.version"));
            System.exit(-1);
            return;
        }

        if (!checkEula()) {
            System.err.println("You need to agree to the EULA to run the server.");
            System.exit(-1);
            return;
        }

        Main_Forge.main(args);
    }

    private static boolean checkEula() throws IOException {
        File eulaFile = new File(EULA_FILE);

        if (eulaFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(eulaFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("eula=")) {
                        String value = line.trim().substring(5); // Get content after "eula="
                        if (value.trim().equalsIgnoreCase("true")) {
                            return true; // EULA already accepted
                        } else {
                            // Explicitly set to false, or some other value, require re-acceptance
                            System.out.println("EULA is currently set to \"" + value.trim() + "\", but needs to be accepted to run the server.");
                            return promptUserForEula(eulaFile, true); // true indicates we're updating existing file
                        }
                    }
                }
            }
        }

        return promptUserForEula(eulaFile, false);
    }

    private static boolean promptUserForEula(File eulaFile, boolean isUpdating) throws IOException {
        System.out.println("By running this server, you agree to the Minecraft EULA.");
        System.out.println("Read the EULA at: " + EULA_URL);
        System.out.print("Do you agree to the EULA? (y/N): ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String input = reader.readLine();
            if (input != null && input.trim().toLowerCase().startsWith("y")) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(eulaFile))) {
                    writer.write("# By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).\n");
                    writer.write("# Generated via Luminara server\n");
                    writer.write("eula=true\n");
                }
                System.out.println("EULA has been " + (isUpdating ? "updated" : "accepted") + " and saved.");
                return true;
            } else {
                System.out.println("EULA not accepted. Server will not start.");
                return false;
            }
        }
    }
}
