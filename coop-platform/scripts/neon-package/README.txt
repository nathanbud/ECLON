Cooperative backend for the Neon TEST database
==============================================

This folder runs the application's backend on this PC, connected over the internet to the test database on Neon.
Test data only: never enter real members' details.

Once
----
1. Install Java 21: Eclipse Temurin 21 from https://adoptium.net (JRE or JDK, Windows x64 .msi).
   In the installer, switch on "Add to PATH" and "Set JAVA_HOME".
2. Install the desktop app (the setup .exe).
3. Put this folder somewhere that stays, e.g. C:\coop-neon (not on the USB stick).

Every time
----------
1. Double-click start-neon.cmd.
   The first time it asks the password of coop_app on Neon and a bootstrap sign-in, and whether to remember them
   (kept encrypted for this Windows account only). Wait until it says "Started CoopCoreApplication".
   Keep the window open: closing it stops the backend.
2. Open the desktop app. In the sign-in screen's Connection window add http://localhost:8092 and choose it.
3. Sign in with the bootstrap name and password, or with a user created in the app (officer1, officer2, ...).

Good to know
------------
- Everything is saved on Neon, so what is created here also shows on the other PC, and the other way round.
- To type new passwords: start-neon.cmd -Forget (from a Command Prompt in this folder).
- If something goes wrong, the log is data\backend.log in this folder.
- After the application is changed, get a new copy of this folder: an old one says the database is newer than it knows.
- biometric.key is the test database's fingerprint key. Keep this folder private, and delete it when the test is over.
