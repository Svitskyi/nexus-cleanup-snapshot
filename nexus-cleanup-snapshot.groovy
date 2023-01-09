import groovy.xml.XmlSlurper

def nexusSnapshotsUrl = "http://localhost:8081/nexus/content/repositories/snapshots"
def nexusCredentials = "admin:admin123"
def olderThanDays = -1
def ga = "com/test/testng-docker"

run(nexusSnapshotsUrl, nexusCredentials, olderThanDays, ga)

def run(def nexusSnapshotsUrl, def nexusCredentials, def olderThanDays, def ga) {
    long MEGABYTE = 1024L * 1024L;
    Long cleanedBytes = 0

    def olderThanDate = getAllVersionsForArtifact(nexusSnapshotsUrl, ga)
            .findAll { snapshotOlderThanDays(olderThanDays, it.date) }

    if (olderThanDate) {
        print "Found $olderThanDate.size artifacts older than $olderThanDays days\n"
        def canCleanup = 0

        olderThanDate.each {
            print "${it.url}\n"
            canCleanup += getTotalSizeOfAVersion(nexusSnapshotsUrl, ga, it.name)
        }
        print "Can cleanup: ${canCleanup / MEGABYTE} Megabytes ($canCleanup bytes)\n"

        def choice = System.console().readLine 'Proceed? (y/n)\n'

        if (choice.equals('y')) {
            olderThanDate
                    .each {
                        Long sizeOfAVersion = getTotalSizeOfAVersion(nexusSnapshotsUrl, ga, it.name)
                        def delete = new URL("$it.url").openConnection()
                        delete.setRequestMethod("DELETE")
                        delete.setRequestProperty('Authorization', "Basic ${nexusCredentials.bytes.encodeBase64().toString()}")
                        def getRC = delete.getResponseCode()
                        println "DELETING: ${it.url}"
                        if (getRC.equals(204)) {
                            println "DELETED: ${it.url} and cleaned $sizeOfAVersion bytes"
                            cleanedBytes += sizeOfAVersion
                        }
                    }
            println "Cleaned ${canCleanup / MEGABYTE} Megabytes ($canCleanup bytes)\n"
        } else {
            print "Exit\n"
        }
    } else {
        print "No artifacts older than $olderThanDays days found\n"
    }
}

private Long getTotalSizeOfAVersion(def nexusSnapshotsUrl, def ga, def v) {

    def artifactUrl = new URL("$nexusSnapshotsUrl/$ga/$v").openConnection()
    def getRC = artifactUrl.getResponseCode()
    if (getRC.equals(200)) {
        def text = artifactUrl.getInputStream().getText().split("\n").collect {
            if ("$it".contains('<link')) {
                return ""
            } else {
                return "$it"
            }
        }.join('\n')

        def slurper = new XmlSlurper()
        def htmlParser = slurper.parseText(text)

        def values = htmlParser.'**'
                .findAll { it.tr }
                .findAll {
                    !"$it".trim().isEmpty() &&
                            !"$it".replace(' ', "").trim().equals('') &&
                            !"$it".contains("Parent Directory")
                }
                .collect {
                    [size: "${it.td[2]}".trim()]
                }

        def totalSize = 0

        values.each {
            if (!it.size.equals('')) {
                totalSize += "${it.size}".toLong()
            }
        }

        totalSize
    }
}

private def getAllVersionsForArtifact(def nexusSnapshotsUrl, def ga) {
    def artifactUrl = new URL("$nexusSnapshotsUrl/$ga").openConnection()
    def getRC = artifactUrl.getResponseCode()
    if (getRC.equals(200)) {
        def text = artifactUrl.getInputStream().getText().split("\n").collect {
            if ("$it".contains('<link') || "$it".contains("&nbsp;")) {
                return ""
            } else {
                return "$it"
            }
        }.join('\n')

        def slurper = new XmlSlurper()

        def htmlParser = slurper.parseText(text)
        def values = htmlParser.'**'
                .findAll { it.tr.@href }
                .findAll {
                    !"$it".trim().isEmpty() &&
                            !"$it".replace(' ', "").trim().equals('') &&
                            !"$it".contains("Parent Directory")
                }.collect {
            [name: "${it.td[0]}".trim(), date: "${it.td[1]}".trim(), url: "$nexusSnapshotsUrl/$ga/${it.td[0]}"]
        }

        def sanitizedObjects = []

        values.each {
            if (!it.name.equals('') && it.name.contains("-SNAPSHOT")) {
                sanitizedObjects << it
            }
        }
        return sanitizedObjects
    }
}

private def snapshotOlderThanDays(int olderThanDays, date) {
    def duration = groovy.time.TimeCategory.minus(new Date(), new Date(date))
//    def values = [
//            "seconds: " + duration.seconds,
//            "min: " + duration.minutes,
//            "hours: " + duration.hours,
//            "days: " + duration.days,
//            "ago: " + duration.ago,
//    ]

    if (duration.days > olderThanDays) {
        return true
    }
    return false
}
