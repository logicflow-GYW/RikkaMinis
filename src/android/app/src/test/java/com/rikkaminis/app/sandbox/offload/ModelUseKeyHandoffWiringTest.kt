package com.rikkaminis.app.sandbox.offload
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class ModelUseKeyHandoffWiringTest {
 @Test fun `passthrough dispatch carries the current instance key`() = assertDispatch("ptRequestJson")
 @Test fun `standard dispatch carries the current instance key`() = assertDispatch("remoteRequestJson")
 @Test fun `carried rotated key wins over stale worker cache`() {
  var fallbackRead=false
  assertEquals("new-key",WorkerKeyHandoff.pickKey("new-key") { fallbackRead=true; "stale-key" })
  assertFalse(fallbackRead)
  assertTrue(WorkerKeyHandoff.carriesKey("new-key"))
  assertFalse(WorkerKeyHandoff.carriesKey(""))
 }
 private fun assertDispatch(request: String) {
  var d:File?=File(System.getProperty("user.dir"))
  var text:String?=null
  while(d!=null){
   val f=File(d,"src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt")
   if(f.isFile){ text=f.readText();break };d=d.parentFile
  }
  assertNotNull("source missing",text)
  assertTrue(text!!.contains("ModelExecutionDispatcher.dispatch(context, $request, apiKey = WorkerKeyHandoff.keyFor(context, instance.id))"))
 }
}
