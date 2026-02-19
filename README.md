# localserver

## Project Architecture (Package Structure)

    /local-server
    ├── src/
    │ ├── com/server/
    │ │ ├── core/
    │ │ │ ├── Main.java # Entry point: loads config & starts engine
    │ │ │ ├── HttpEngine.java # The Event Loop (Selector management)
    │ │ │ └── ConnectionState.java # Manages partial reads/writes per client
    │ │ ├── config/
    │ │ │ ├── ConfigParser.java # Parses JSON/Conf file
    │ │ │ └── ServerConfig.java # Data models for routes/ports
    │ │ ├── http/
    │ │ │ ├── Request.java # Manual parser (Headers, Body, Chunks)
    │ │ │ ├── Response.java # Constructor (Status codes, Headers)
    │ │ │ └── ProtocolHandler.java # Logic for GET, POST, DELETE
    │ │ ├── handlers/
    │ │ │ ├── FileHandler.java # Static content & Directory listing
    │ │ │ ├── CGIHandler.java # ProcessBuilder logic
    │ │ │ └── UploadHandler.java # Multi-part/body saving
    │ │ └── utils/
    │ │     ├── Logger.java # Non-blocking logging
    │ │     └── HttpError.java # Enum for 400, 404, 500, etc.
    ├── config.json # Server settings
    ├── www/ # Root directory for static files
    └── error_pages/ # Custom HTML for errors
