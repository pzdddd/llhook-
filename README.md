<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your Android app

This repository contains everything you need to open and build the app locally.

View your app in AI Studio: https://ai.studio/apps/604f3273-eadd-40b3-8359-979c4731fdf4

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio), JDK 17


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Make sure Android Studio uses JDK 17 for Gradle sync and builds
4. Let Android Studio import the Gradle project with the included wrapper
5. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
6. Run the app on an emulator or physical device
