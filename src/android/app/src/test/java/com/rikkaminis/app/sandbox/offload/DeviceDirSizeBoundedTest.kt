package com.rikkaminis.app.sandbox.offload
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class DeviceDirSizeBoundedTest {
 @Test fun `directory link loop terminates and outside payload is not counted`() {
  val root=Files.createTempDirectory("device-size").toFile()
  val outside=Files.createTempDirectory("device-outside").toFile()
  try {
   File(root,"a").writeText("12345")
   File(outside,"private").writeText("outside")
   Files.createSymbolicLink(File(root,"loop").toPath(),root.toPath())
   Files.createSymbolicLink(File(root,"out").toPath(),outside.toPath())
   val result=deviceDirectorySize(root)
   assertEquals(5L,result.bytes)
   assertEquals(4L,result.visited)
   assertFalse(result.truncated)
   assertTrue(result.errors.isEmpty())
  } finally {
   Files.deleteIfExists(File(root,"loop").toPath());Files.deleteIfExists(File(root,"out").toPath())
   root.deleteRecursively();outside.deleteRecursively()
  }
 }
 @Test fun `normal directory retains exact apparent file sizes`() {
  val root=Files.createTempDirectory("device-normal").toFile()
  try {
   File(root,"sub").mkdir();File(root,"a").writeBytes(ByteArray(7));File(root,"sub/b").writeBytes(ByteArray(3))
   val r=deviceDirectorySize(root);assertEquals(10L,r.bytes);assertFalse(r.truncated)
  } finally {root.deleteRecursively()}
 }
 @Test fun `entry cap is inclusive and reports truncation`() {
  val root=Files.createTempDirectory("device-entry").toFile()
  try {
   File(root,"a").writeText("x")
   assertFalse(deviceDirectorySize(root,2).truncated)
   val limited=deviceDirectorySize(root,1);assertEquals(1L,limited.visited);assertTrue(limited.truncated)
  } finally {root.deleteRecursively()}
 }
 @Test fun `depth cap reports partial instead of lying about a full size`() {
  val root=Files.createTempDirectory("device-depth").toFile()
  try {
   File(root,"sub").mkdir();File(root,"sub/a").writeText("abc")
   val limited=deviceDirectorySize(root,100,1);assertTrue(limited.truncated);assertEquals(0L,limited.bytes)
   assertEquals(3L,deviceDirectorySize(root,100,2).bytes)
  } finally {root.deleteRecursively()}
 }
}
