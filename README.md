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

### Resource Types

You can share the following types of resources through Joatse tunnels:

#### 1. HTTP Services (`--shareHttp`)

Share a local HTTP/HTTPS service through the tunnel.

**Format:** `[description#]URL`

**Examples:**
```bash
# Basic HTTP share
--shareHttp=http://localhost:8080

# With custom description
--shareHttp="My Web App#http://localhost:3000"

# HTTPS service
--shareHttp=https://localhost:8443
```

**Variants:**
- `--shareHttpUnsafe`: Share HTTP service with relaxed security checks
- `--shareHttpHideProxy`: Share HTTP service while hiding proxy headers

#### 2. TCP Ports (`--shareTcp`)

Share a raw TCP port (works with any TCP-based protocol: SSH, database, custom services, etc.).

**Format:** `[description#]targetHost:port`

**Examples:**
```bash
# Share SSH server
--shareTcp=localhost:22

# Share database with description
--shareTcp="PostgreSQL#localhost:5432"

# Share custom service
--shareTcp=192.168.1.100:9000
```

#### 3. SOCKS5 Proxy (`--shareSocks5`)

Share a SOCKS5 proxy to allow remote access through your network.

**Format:** `targetHost[:port]` or `*`

**Examples:**
```bash
# Allow SOCKS5 to any destination
--shareSocks5=*

# Restrict to specific host
--shareSocks5=internalserver.local

# Restrict to specific host and port
--shareSocks5=internalserver.local:8080
```

**Note:** Multiple targets can be specified with multiple `--shareSocks5` parameters.

#### 4. Remote Commands via SSH (`--shareCommand`)

Execute commands on a remote system via SSH and share the result through the tunnel.

**Format:** `[description#]user@[sshHost][:sshPort]@commandLine`

**Examples:**
```bash
# Simple bash shell
--shareCommand=root@@/bin/bash

# With custom SSH host
--shareCommand=admin@server.local@/bin/bash

# With custom port
--shareCommand=user@server.local:2222@/bin/bash

# Command with arguments (use quotes)
--shareCommand="My Script#root@localhost@/usr/local/bin/script.sh arg1 arg2"

# Command with spaces in arguments
--shareCommand='Deploy Tool#deployer@@/opt/deploy "environment name" --verbose'
```

**Notes:**
- Default SSH host is `localhost` if not specified
- Default SSH port is `22` if not specified
- Command line arguments with spaces must be quoted or escaped

#### 5. File Sharing (`--shareFile`)

Share individual files from the target system through the tunnel.

**Format:** `[description#]path/to/file`

**Examples:**
```bash
# Share a single file
--shareFile=/var/log/application.log

# Share with custom description
--shareFile="Application Log#/var/log/app.log"

# Share configuration file
--shareFile="Config#/etc/myapp/config.json"

# Share from current directory (relative path)
--shareFile=./report.pdf

# Windows path
--shareFile="C:\logs\application.log"

# Multiple files
--shareFile=/var/log/app.log --shareFile="Report#/home/user/report.pdf"
```

**Features:**
- **Automatic path conversion**: Relative paths are converted to absolute paths before sharing
- **File validation**: Target validates that file exists, is readable, and is a regular file (or symlink)
- **Auto-description**: If no description provided, uses the filename (e.g., `application.log`)
- **Metadata support**: Cloud service can query file metadata (name, size, last modified)
- **Range requests**: Supports partial file downloads (HTTP Range header)
- **Read-only access**: Files are shared for reading only, no write access
- **Symlink support**: Symlinks are followed; metadata reflects target file, but filename is the symlink name

**Use Cases:**
- Share log files for remote inspection
- Provide configuration files for download
- Share build artifacts or generated reports
- Temporary file sharing without setting up a web server
- Read-only access to specific files

**Security Notes:**
- Files are validated at startup (existence, readability)
- Only files explicitly specified are shared
- Symlinks are followed automatically
- Access control is managed by the cloud service
- Use absolute paths in confirmation to prevent confusion

### General Parameters

- `--cloud.url=wss://HOST:PORT/connection`: WebSocket Secure connection to the joatse-cloud service
- `--daemonMode=true`: Run in daemon mode (non-interactive, will retry connections automatically)
- `--preconfirmed=UUID`: (Optional) Use a preconfirmed share UUID for automatic authorization
- `--autoAuthorizeByHttpUrl`: (Optional) Automatically authorize connections based on HTTP URL (you don't need to authorize incoming IP addresses. You trust them if they know the URL)
- `--retryCount=N`: (Optional) Number of connection retry attempts (default: 5 in interactive mode, infinite in daemon mode)
- `--qr-mode=MODE`: (Optional) QR code display mode: `AUTO`, `CONSOLE`, `NONE` (default: `AUTO`)

### Multiple Resources

You can share multiple resources simultaneously:

```bash
java -jar joatse-target-WHATEVER.jar \
  --shareHttp=http://localhost:8080 \
  --shareTcp=localhost:22 \
  --shareSocks5=* \
  --shareFile=/var/log/app.log \
  --cloud.url=wss://your-server:9011/connection \
  --daemonMode=true
```
