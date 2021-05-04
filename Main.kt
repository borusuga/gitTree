package gitinternals

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.lang.Error
import java.lang.Exception
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.InflaterInputStream

abstract class ObjectContent(private val type: String, val byteContent: ByteArray) {
    abstract fun printObject()

    fun contentToString(): String {
        return String(byteContent.takeLastWhile { it.toInt() != 0 }.toByteArray())
    }

    fun printContent() {
        println("*${type.toUpperCase()}*")
        printObject()
    }
}

class BlobContent(val hash: String, type: String, byteContent: ByteArray) : ObjectContent(type, byteContent) {
    override fun printObject() {
        println(contentToString())
    }
}

class CommitContent(val hash: String, type: String, byteContent: ByteArray) : ObjectContent(type, byteContent) {
    private val content = contentToString().lines().dropLastWhile { it.isEmpty() }
    override fun printObject() {
        val data = content.takeWhile { it.isNotEmpty() }
        val dataMap = data.map {
            var key = it.substringBefore(' ')
            val value = it.substringAfter(' ')
            val parsedValue = when (key) {
                in listOf("author", "committer") -> {
                    val groups = Regex("([\\w\\s]+) <([\\w.@]+)> (\\d+) (\\+\\d+)").find(value)!!
                    val groupValues = groups.groupValues
                    val name = groupValues[1]
                    val email = groupValues[2]
                    val timestamp = groupValues[3]
                    val instant = Instant.ofEpochSecond(timestamp.toLong())
                    val timeZone = groupValues[4]
                    val formatter = DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss xxx")
                        .withZone(ZoneId.of(timeZone))
                    val timestampType = if (key == "author") "original" else "commit"
                    "$name $email $timestampType timestamp: ${formatter.format(instant)}"
                }
                else -> {
                    if (key == "parent") {
                        key = "parents"
                    }
                    value
                }
            }
            key to parsedValue
        }.toMutableList()
        for ((key, value) in dataMap) {
            println("$key: $value")
        }
        val message = content.takeLastWhile { it.isNotEmpty() }.joinToString("\n")
        println("commit message:\n$message")
    }
}

class TreeLine(val name: String, val permission: String, val hash: String) {
    override fun toString(): String {
        return "$permission $hash $name"
    }
}

class TreeContent(val hash: String, type: String, byteContent: ByteArray) : ObjectContent(type, byteContent) {
    override fun printObject() {
        val zerosInContent = mutableListOf<Int>()
        var cutFrom = 0
        val lines = mutableListOf<TreeLine>()

        var i = 0
        val byteContentList = byteContent.toList()
        while (i < byteContent.lastIndex) {
            val byte = byteContent[i]
            if (byte.toInt() == 0) {
                zerosInContent.add(i)
                val startShaIndex = i + 1
                val stopShaIndex = startShaIndex + 20
                val nameWithPermission = String(byteContentList.subList(cutFrom, i).toByteArray())
                val (permission, name) = nameWithPermission.split(" ")
                lines.add(
                    TreeLine(
                        name,
                        permission,
                        byteContentList.subList(startShaIndex, stopShaIndex).joinToString("") { "%x".format(it) }
                    )
                )
                cutFrom = stopShaIndex
                i = stopShaIndex - 1
            }
            i++
        }
        print(lines.joinToString("\n"))
    }
}

class GitReader(path: String) {
    private val objectDir = "$path/objects"

    fun read(hash: String): ObjectContent {
        val prefix = hash.take(2)
        val file = hash.drop(2)
        val path = "$objectDir/$prefix/$file"
        try {
            val fis = FileInputStream(path)
            val iis = InflaterInputStream(fis)
            val bytes = iis.readAllBytes().toList()
            iis.close()
            fis.close()
            val meta = String(bytes.takeWhile { it.toInt() != 0 }.toByteArray())
            val byteContent = bytes.dropWhile { it.toInt() != 0 }.drop(1).toByteArray()
            val (type, _) = meta.split(" ")
            return when (type) {
                "blob" -> BlobContent(hash, type, byteContent)
                "commit" -> CommitContent(hash, type, byteContent)
                "tree" -> TreeContent(hash, type, byteContent)
                else -> throw Error("Missing type: $type")
            }
        } catch (e: FileNotFoundException) {
            val absolutePath = Paths.get(path).toAbsolutePath()
            throw Exception("File not found: $absolutePath")
        }
    }
}

class Branches(dir: String) {
    private val dir = dir
    private val currentBranchPath = "$dir/HEAD"
    private val brancesPath = "$dir/refs/heads"
    val mainBranch = getRefToMainBranch()
    val allFiles = getRefsToAllBranches()

    fun getRefToMainBranch(): String {
        try {
            val lines = File(currentBranchPath).readText()
            return "${this.dir}/${lines.substringAfter(' ').substringBefore('\n')}"
        } catch (e: FileNotFoundException) {
            val absolutePath = Paths.get(currentBranchPath).toAbsolutePath()
            throw Exception("File not found: $absolutePath")
        }
    }

    fun getRefsToAllBranches(): List<String> {
        try {
            val files = File(brancesPath).listFiles()
            var allFileNames = mutableListOf<String>()
            for (each in files) {
                allFileNames.add("${each.toString().substringAfterLast('/')}")
            }
            allFileNames.sort()
            return allFileNames
        } catch (e: FileNotFoundException) {
            val absolutePath = Paths.get(brancesPath).toAbsolutePath()
            throw Exception("File not found: $absolutePath")
        }
    }

    fun printBranches() {
        val mainBName = mainBranch.substringAfterLast('/')
        for (each in allFiles) {
            if (each == mainBName) println("* $each") else println("  $each")
        }
    }

}

class Log(dir: String, bname: String) {
    val pathBranch = "$dir/refs/heads/$bname"
    var lastCommitHash = getLastCommitHash(dir)
    val dir = dir
    var notOrphan = true

    fun getLastCommitHash(dir: String): String {
        try {
            val lines = File(pathBranch).readText()
            return lines.substringBefore('\n')
        } catch (e: FileNotFoundException) {
            val absolutePath = Paths.get(pathBranch).toAbsolutePath()
            throw Exception("File not found: $absolutePath")
        }
    }

    fun contentToString(byteContent: ByteArray): String {
        return String(byteContent.takeLastWhile { it.toInt() != 0 }.toByteArray())
    }

    fun printAll(hash: String) {
        if (notOrphan) {
            val prefix = hash.take(2)
            val file = hash.drop(2)
            val path = "$dir/objects/$prefix/$file"
            try {
                val fis = FileInputStream(path)
                val iis = InflaterInputStream(fis)
                val bytes = iis.readAllBytes().toList()
                iis.close()
                fis.close()
                val meta = String(bytes.takeWhile { it.toInt() != 0 }.toByteArray())
                val byteContent = bytes.dropWhile { it.toInt() != 0 }.drop(1).toByteArray() // content of the commit file
                val (type, _) = meta.split(" ")
                print("${type.substring(0, 1).toUpperCase()}${type.substring(1, type.length)}: $hash\n")


                val content = contentToString(byteContent).lines().dropLastWhile { it.isEmpty() }
                val data = content.takeWhile { it.isNotEmpty() }
                val dataMap = data.map {
                    var key = it.substringBefore(' ')
                    val value = it.substringAfter(' ')
                    val parsedValue = when (key) {
                        "committer" -> {
                            val groups = Regex("([\\w\\s]+) <([\\w.@]+)> (\\d+) (\\+\\d+)").find(value)!!
                            val groupValues = groups.groupValues
                            val name = groupValues[1]
                            val email = groupValues[2]
                            val timestamp = groupValues[3]
                            val instant = Instant.ofEpochSecond(timestamp.toLong())
                            val timeZone = groupValues[4]
                            val formatter = DateTimeFormatter
                                .ofPattern("yyyy-MM-dd HH:mm:ss xxx")
                                .withZone(ZoneId.of(timeZone))
                            "$name $email commit timestamp: ${formatter.format(instant)}"
                        }
                        else -> {
                            value
                        }
                    }
                    key to parsedValue
                }.toMutableList()
                val parentsHashList = mutableListOf<String>()
                var isParent = false
                for ((key, value) in dataMap) {
                    if (key == "committer") {
                        println(value)
                    } else if (key == "parent") {
                            parentsHashList.add(value)
                            isParent = true
                    }
                }
                if (!isParent) notOrphan = false
                val message = content.takeLastWhile { it.isNotEmpty() }.joinToString("\n")
                println(message)
                println()
                //for (each in parentsHashList){
                    //notOrphan = true
                if (parentsHashList.size > 0) printAll(parentsHashList[0])
                //}

            } catch (e: FileNotFoundException) {
                val absolutePath = Paths.get(path).toAbsolutePath()
                throw Exception("File not found: $absolutePath")
            }
        }
    }
}

// ---------------------------------------------------------

class Tree(dir: String, hash: String) {
    var commitHash = hash
    val dir = dir
    // tmpPath = ""
    fun contentToString(byteContent: ByteArray): String {
        return String(byteContent.takeLastWhile { it.toInt() != 0 }.toByteArray())
    }

    fun printAll() {
        val prefix = commitHash.take(2)
        val file = commitHash.drop(2)
        val path = "$dir/objects/$prefix/$file"
        try {
            val fis = FileInputStream(path)
            val iis = InflaterInputStream(fis)
            val bytes = iis.readAllBytes().toList()
            iis.close()
            fis.close()
            val meta = String(bytes.takeWhile { it.toInt() != 0 }.toByteArray())
            val byteContent = bytes.dropWhile { it.toInt() != 0 }.drop(1).toByteArray() // content of the commit file
            val (type, _) = meta.split(" ")
            //print("${type.substring(0, 1).toUpperCase()}${type.substring(1, type.length)}: $commitHash\n")

            val content = contentToString(byteContent).lines().dropLastWhile { it.isEmpty() }
            val data = content.takeWhile { it.isNotEmpty() }
            val dataMap = data.map {
                var key = it.substringBefore(' ')
                val value = it.substringAfter(' ')
                key to value
            }.toMutableList()
            for ((key, value) in dataMap) {
                if (key == "tree") {
                    printTree(value, "")
                }
            }
        } catch (e: FileNotFoundException) {
            val absolutePath = Paths.get(path).toAbsolutePath()
            throw Exception("File not found: $absolutePath")
        }
    }

    fun printTree(hash: String, tmpPath: String) {
        val prefix = hash.take(2)
        val file = hash.drop(2)
        val path = "$dir/objects/$prefix/$file"
        try {
            val fis = FileInputStream(path)
            val iis = InflaterInputStream(fis)
            val bytes = iis.readAllBytes().toList()
            iis.close()
            fis.close()
            val meta = String(bytes.takeWhile { it.toInt() != 0 }.toByteArray())
            val byteContent = bytes.dropWhile { it.toInt() != 0 }.drop(1).toByteArray()
            val (type, _) = meta.split(" ")
            when (type) {
                "blob" -> println(tmpPath)
                "tree" -> {
                    val zerosInContent = mutableListOf<Int>()
                    var cutFrom = 0
                    val lines = mutableListOf<TreeLine>()
                    var i = 0
                    val byteContentList = byteContent.toList()
                    while (i < byteContent.lastIndex) {
                        val byte = byteContent[i]
                        if (byte.toInt() == 0) {
                            zerosInContent.add(i)
                            val startShaIndex = i + 1
                            val stopShaIndex = startShaIndex + 20
                            val nameWithPermission = String(byteContentList.subList(cutFrom, i).toByteArray())
                            val (permission, name) = nameWithPermission.split(" ")
                            lines.add(
                                TreeLine(
                                    name,
                                    permission,
                                    byteContentList.subList(startShaIndex, stopShaIndex).joinToString("") { "%x".format(it) }
                                )
                            )
                            cutFrom = stopShaIndex
                            i = stopShaIndex - 1
                        }
                        i++
                    }
                    for (each in lines) {
                        var newPath = ""
                        if (tmpPath.isEmpty()) {
                            newPath = each.name
                        } else {
                            newPath = "$tmpPath/${each.name}"
                        }
                        printTree(each.hash, newPath)
                    }
                }
                else -> throw Error("Missing type: $type")
            }
        } catch (e: FileNotFoundException) {
            println(tmpPath)
        }
    }

}


fun main() {
    println("Enter .git directory location:")
    val gitDir = readLine()!!.toString()
    println("Enter command:")
    val command = readLine()!!.toString()
    if (command == "cat-file") {
        println("Enter git object hash:")
        val hash = readLine()!!.toString()
        val reader = GitReader(gitDir)
        val gitObject = reader.read(hash)
        gitObject.printContent()
    } else if (command == "list-branches") {
        val branches = Branches(gitDir)
        branches.printBranches()
    } else if (command == "log") {
        println("Enter branch name:")
        val bname = readLine()!!.toString()
        val logObj = Log(gitDir,bname)
        logObj.printAll(logObj.lastCommitHash)
    } else if (command == "commit-tree") {
        println("Enter commit hash")
        val hash = readLine()!!.toString()
        val treeObj = Tree(gitDir, hash)
        treeObj.printAll()
    } else println("no such command")
}