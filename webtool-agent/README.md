# webtool-agent

Target-side lightweight command execution agent.

Build:

```bash
mvn -q -DskipTests package
```

Run:

```bash
java -jar target/webtool-agent-1.0.0-SNAPSHOT.jar \
  --server.port=18080 \
  --agent.token=replace-with-strong-secret
```

Configure webtool main service:

```yaml
app:
  agent:
    enabled: true
    port: 18080
    token: replace-with-strong-secret
```

When enabled, webtool builds the Agent URL from the selected server host:

```text
http://{server.host}:{app.agent.port}/api/v1/terminal/stream
```

Protocol endpoint:

```text
ws://target-host:18080/api/v1/terminal/stream
```

The agent uses pty4j to create a real pseudo terminal, so interactive tools such as vim, top, mysql, su, and sudo can behave like they do through an SSH terminal.

Security:

```yaml
agent:
  token: replace-with-strong-secret
  security:
    require-token: true
    allowed-clients:
      - 10.0.0.5   # webtool main service IP
```

Also restrict the Agent port at the firewall/security-group level so only the webtool main service can reach it.
