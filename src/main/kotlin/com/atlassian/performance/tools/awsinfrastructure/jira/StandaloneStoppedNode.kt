package com.atlassian.performance.tools.awsinfrastructure.jira

import com.atlassian.performance.tools.aws.api.Storage
import com.atlassian.performance.tools.awsinfrastructure.api.jira.StartedNode
import com.atlassian.performance.tools.infrastructure.api.jira.JiraLaunchTimeouts
import com.atlassian.performance.tools.infrastructure.api.jvm.JavaDevelopmentKit
import com.atlassian.performance.tools.infrastructure.api.os.MonitoringProcess
import com.atlassian.performance.tools.infrastructure.api.os.OsMetric
import com.atlassian.performance.tools.ssh.api.Ssh
import com.atlassian.performance.tools.ssh.api.SshConnection
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.net.URI
import java.time.Duration
import java.time.Duration.ofMinutes
import java.time.Duration.ofSeconds
import java.time.Instant.now

internal data class StandaloneStoppedNode(
    private val name: String,
    override val jiraHome: String,
    private val analyticLogs: String,
    private val resultsTransport: Storage,
    private val unpackedProduct: String,
    private val osMetrics: List<OsMetric>,
    private val launchTimeouts: JiraLaunchTimeouts,
    private val jdk: JavaDevelopmentKit,
    override val ssh: Ssh
) : StoppedNode {
    private val logger: Logger = LogManager.getLogger(this::class.java)

    override fun start(): StartedNode {
        logger.info("Starting '$name'...")
        val monitoringProcesses = mutableListOf<MonitoringProcess>()

        ssh.newConnection().use {
            osMetrics.forEach { metric ->
                monitoringProcesses.add(metric.startMonitoring(it))
            }
            startJira(it)
            monitoringProcesses.add(jdk.jstatMonitoring.startMonitoring(it, pid(it)))
            waitForUpgrades(it)
        }

        logger.info("'$name' is started")

        return StartedNode(
            name = name,
            jiraHome = jiraHome,
            analyticLogs = analyticLogs,
            resultsTransport = resultsTransport,
            unpackedProduct = unpackedProduct,
            monitoringProcesses = monitoringProcesses,
            ssh = ssh
        )
    }

    private fun startJira(
        ssh: SshConnection
    ) {
        ssh.execute(
            """
            |${jdk.use()}
            |./$unpackedProduct/bin/start-jira.sh
            """.trimMargin(),
            ofMinutes(1)
        )
    }

    private fun pid(
        ssh: SshConnection
    ): String {
        return ssh.execute("cat $unpackedProduct/work/catalina.pid").output.trim()
    }

    private fun waitForUpgrades(
        ssh: SshConnection
    ) {
        val upgradesEndpoint = URI("http://admin:admin@localhost:8080/rest/api/2/upgrade")
        waitForStatusToChange(
            statusQuo = "000",
            timeout = launchTimeouts.offlineTimeout,
            ssh = ssh,
            uri = upgradesEndpoint
        )
        waitForStatusToChange(
            statusQuo = "503",
            timeout = launchTimeouts.initTimeout,
            ssh = ssh,
            uri = upgradesEndpoint
        )
        ssh.execute(
            cmd = "curl --silent --retry 6 -X POST $upgradesEndpoint",
            timeout = ofSeconds(15)
        )
        waitForStatusToChange(
            statusQuo = "303",
            timeout = launchTimeouts.upgradeTimeout,
            ssh = ssh,
            uri = upgradesEndpoint
        )
    }

    private fun waitForStatusToChange(
        statusQuo: String,
        uri: URI,
        timeout: Duration,
        ssh: SshConnection
    ) {
        val backoff = ofSeconds(10)
        val deadline = now() + timeout
        while (true) {
            val currentStatus = ssh.safeExecute(
                cmd = "curl --silent --write-out '%{http_code}' --output /dev/null -X GET $uri",
                timeout = launchTimeouts.unresponsivenessTimeout
            ).output
            if (currentStatus != statusQuo) {
                break
            }
            if (deadline < now()) {
                throw Exception("$uri failed to get out of $statusQuo status within $timeout")
            }
            Thread.sleep(backoff.toMillis())
        }
    }
}