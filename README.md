# fileshareAPP

Small Java 25 desktop client/server for transferring files over a local TCP connection.

## Build

```bash
./mvnw verify
```

Start `FileSyncServer` and then `FileSyncClient`. Optional `server_config.properties` and
`client_config.properties` files can override the default localhost port 5000 configuration.
