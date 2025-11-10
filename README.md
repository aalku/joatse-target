# Joatse Target

**J**ava **O**pen **A**ccessible **T**unneling **S**oftware **E**lement

This is the Joatse target client software repository.

Please read [joatse-cloud/README.md](https://github.com/aalku/joatse-cloud/blob/main/README.md) for information about this project.

## Command Line Usage

### Standard Command Format

```bash
java -jar joatse-target-WHATEVER.jar \
  --shareHttp=http://localhost:8080 \
  --cloud.url=wss://localhost:PORT/connection \
  --daemonMode=true
```

### Parameters

- `--shareHttp=URL`: The local HTTP service to expose through the tunnel (e.g., `http://localhost:8080`)
- `--cloud.url=wss://HOST:PORT/connection`: WebSocket Secure connection to the joatse-cloud service
- `--daemonMode=true`: Run in daemon mode (non-interactive)
- `--preconfirmed=UUID`: (Optional) Use a preconfirmed share UUID for automatic authorization