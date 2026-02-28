# 📡 ChatBox – Proximity Mesh Chat for Android

ChatBox is an experimental peer-to-peer proximity messaging app built with:

- Kotlin
- Jetpack Compose
- Google Nearby Connections API

The goal is to explore decentralized communication between nearby devices without requiring internet connectivity.

---

## 🚀 Vision

This project aims to evolve into:

- A **proximity-based public chat**
- Private peer-to-peer messaging (DM)
- Auto-discovery mesh networking
- IoT broadcast integration (e.g. bike stations reporting availability, free parkings)
- Eventually: store-and-forward mesh topology



---

## 🧠 Core Concept

Devices in physical proximity can:

- Automatically discover each other
- Connect using Bluetooth/WiFi Direct (via Nearby API)
- Broadcast public messages
- Send private direct messages (proximity)
- Receive data from simulated IoT nodes (e.g. bike stations, lockers, parkings, metro, bus station)

No central server required.

---

## 🏗 Architecture

### Networking
- Google Nearby Connections
- Strategy: `P2P_CLUSTER`
- Auto advertising + auto discovery

### UI
- Jetpack Compose
- Public chat tab
- DM tab
- Station proximity simulation tab


## 📲 Features

- Public broadcast chat
- Direct peer-to-peer messaging
- Auto connection (no manual host/discover)
- Device proximity simulation
- IoT-style station updates

---

## ⚙️ Permissions Required

- ACCESS_COARSE_LOCATION
- ACCESS_FINE_LOCATION
- BLUETOOTH_SCAN
- BLUETOOTH_CONNECT
- BLUETOOTH_ADVERTISE
- NEARBY_WIFI_DEVICES (Android 13+)

Location must be enabled on device.

---

## 🛠 How to Run

1. Clone repository
2. Open in Android Studio
3. Sync Gradle
4. Run on physical Android device or emulated
5. Grant permissions

To test real networking:
- Install on two Android devices
- Switch to REAL mode
- Ensure Bluetooth and Location are enabled

---


 
Embedded Systems & Distributed Architectures  
IMT Atlantique
