package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.PendingReport
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EgressAuditProbeTest {
 private val context: Application get() = RuntimeEnvironment.getApplication()
 private val url = "https://firebasestorage.googleapis.com/v0/b/fixture/o/reports%2Fcampaign%2Ftester%2Ffull.jpg?alt=media&token=fixture"
 private val thumb = url.replace("full.jpg", "thumb_full.jpg")
 private fun jpeg(): ByteArray {
  val bmp=Bitmap.createBitmap(36,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
  return ByteArrayOutputStream().also{bmp.compress(Bitmap.CompressFormat.JPEG,80,it);bmp.recycle()}.toByteArray()
 }
 @Before fun setup(){
  Dispatchers.setMain(UnconfinedTestDispatcher())
  ScreenshotCachePolicy.isPrivateDiskCacheEnabled=false
  ScreenshotManager.clear(context);ScreenshotManager.currentAccountId="audit_account"
  ImageHashHelper.clearCache();ImageInstrumentation.reset();ImageInstrumentation.isEnabled=true
 }
 @After fun teardown(){
  ScreenshotManager.customHttpClient=null;ScreenshotManager.clear(context)
  ScreenshotCachePolicy.isPrivateDiskCacheEnabled=false
  ImageInstrumentation.isEnabled=false;ImageInstrumentation.reset();Dispatchers.resetMain()
 }
 @Test fun probe01_preparedThumbnailMustSurviveDurablePendingPersistence(){
  WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  val id=UUID.randomUUID().toString();val fullFile=PreparedScreenshotCache.newFile(context,id).apply{writeBytes(jpeg())}
  val thumbFile=PreparedScreenshotCache.newThumbnailFile(context,id).apply{writeBytes(jpeg())}
  val prepared=PreparedScreenshot(id,fullFile,"0123456789abcdef",fullFile.length(),"image/jpeg",thumbFile,thumbFile.length())
  val request=ReportSubmitManager.SubmitRequest("campaign","Audit","tester",5,"fixture",prepared,null,null,false,id)
  val manager=ReportSubmitManager(context)
  val method=ReportSubmitManager::class.java.getDeclaredMethod("persist",ReportSubmitManager.SubmitRequest::class.java).apply{isAccessible=true}
  val pending=method.invoke(manager,request) as PendingReport
  assertNotNull("Worker only uploads request.thumbnailPath; persistence must populate it",pending.thumbnailPath)
  assertTrue(java.io.File(pending.thumbnailPath!!).isFile)
 }
 @Test fun probe02_thumbnail404FallbackMustWorkOnSecondDisplay()=runTest{
  val bytes=jpeg()
  ScreenshotManager.customHttpClient=OkHttpClient.Builder().addInterceptor{chain->
   val missing=chain.request().url.toString()==thumb
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(missing)404 else 200)
    .message(if(missing)"Not Found" else "OK").header("Cache-Control","private, no-store")
    .body((if(missing)ByteArray(0) else bytes).toResponseBody("image/jpeg".toMediaType())).build()
  }.build()
  val loader=ImageLoader.Builder(context).components{add(ScreenshotCoilInterceptor(context))}.build()
  try{
   val report=TesterReport(id="report",campaignId="campaign",userId="tester",screenshotUrl=url,thumbnailUrl=thumb,createdAt=Instant.now().toString())
   val request=ScreenshotRequestFactory.createRequest(context,report,ScreenshotVariant.THUMBNAIL)!!
   assertTrue(loader.execute(request) is SuccessResult)
   assertTrue("Tombstoned thumbnail must still fall back to cached full on re-entry",loader.execute(request) is SuccessResult)
  }finally{loader.shutdown()}
 }
 @Test fun probe03_diskCacheMustRequireTrustedExpiry(){
  ScreenshotCachePolicy.isPrivateDiskCacheEnabled=true
  val stored=PrivateScreenshotDiskCache.put(context,"audit",0,ScreenshotVariant.FULL,url,jpeg(),"private, max-age=60",null,null)
  assertFalse("Missing expiry/authorization contract must fail closed",stored)
 }
 @Test fun probe04_diskCacheMustRespectServerMaxAge(){
  ScreenshotCachePolicy.isPrivateDiskCacheEnabled=true
  assertTrue(PrivateScreenshotDiskCache.put(context,"audit",0,ScreenshotVariant.FULL,url,jpeg(),"private, max-age=60",Instant.now().toString(),false))
  val file=PrivateScreenshotDiskCache.cacheDir(context).walkTopDown().single{it.isFile&&it.extension=="bin"}
  RandomAccessFile(file,"r").use{raf->raf.readInt();val expires=raf.readLong();val cached=raf.readLong()
   assertTrue("max-age=60 must not become 30-minute freshness: ttl=${expires-cached}",expires-cached<=62_000L)
  }
 }
 @Test fun probe05_serverClockBehindMustNotImmediatelyInvalidateValidCache(){
  ScreenshotCachePolicy.isPrivateDiskCacheEnabled=true
  val serverNow=System.currentTimeMillis()-3_600_000L
  assertTrue(PrivateScreenshotDiskCache.put(context,"audit",0,ScreenshotVariant.FULL,url,jpeg(),"private, max-age=60",Instant.ofEpochMilli(serverNow).toString(),false,serverNow))
  assertNotNull("Server Date skew must be applied consistently during get",PrivateScreenshotDiskCache.get(context,"audit",0,ScreenshotVariant.FULL,url))
 }
 @Test fun probe06_clearDuringBodyReadMustNotReviveDiskFiles()=runTest{
  ScreenshotCachePolicy.isPrivateDiskCacheEnabled=true
  val started=CountDownLatch(1);val release=CountDownLatch(1);val closed=CountDownLatch(1);val bytes=jpeg()
  ScreenshotManager.customHttpClient=OkHttpClient.Builder().addInterceptor{chain->
   val source=object:ForwardingSource(Buffer().write(bytes)){
    var first=true
    override fun read(sink:Buffer,byteCount:Long):Long{if(first){first=false;started.countDown();check(release.await(5,TimeUnit.SECONDS))};return super.read(sink,byteCount)}
    override fun close(){super.close();closed.countDown()}
   }.buffer()
   val body=object:ResponseBody(){override fun contentType()="image/jpeg".toMediaType();override fun contentLength()=bytes.size.toLong();override fun source()=source}
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
    .header("Cache-Control","private, max-age=60").body(body).build()
  }.build()
  val call=async(Dispatchers.IO){ScreenshotManager.getOrFetchBitmap(context,url,descriptor=ScreenshotDescriptor(url,Instant.now().toString(),false))}
  try{
   assertTrue(withContext(Dispatchers.IO){started.await(5,TimeUnit.SECONDS)})
   ScreenshotManager.clear(context);release.countDown()
   try{call.await()}catch(_:CancellationException){}
   assertTrue(withContext(Dispatchers.IO){closed.await(5,TimeUnit.SECONDS)})
   val files=PrivateScreenshotDiskCache.cacheDir(context).walkTopDown().filter{it.isFile&&it.extension=="bin"}.toList()
   assertTrue("In-flight response wrote disk files after logout/clear: ${files.size}",files.isEmpty())
  }finally{release.countDown();call.cancel()}
 }
 @Test fun probe07_runtimeLoaderMustCountOneMemoryHitOnce()=runTest{
  val bytes=jpeg()
  ScreenshotManager.customHttpClient=OkHttpClient.Builder().addInterceptor{chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody("image/jpeg".toMediaType())).build()}.build()
  val loader=ImageLoader.Builder(context).components{add(ScreenshotCoilInterceptor(context))}.eventListener(ImageInstrumentation.createEventListener()).build()
  try{
   val req=ImageRequest.Builder(context).data(url).build();assertTrue(loader.execute(req) is SuccessResult)
   ImageInstrumentation.reset();assertTrue(loader.execute(req) is SuccessResult)
   assertEquals("Runtime event listener and interceptor must not both count same hit",1,ImageInstrumentation.snapshot().memoryHits)
  }finally{loader.shutdown()}
 }
 @Test fun probe08_diskDecodeMustPreserveDHashBasis(){
  ScreenshotCachePolicy.isPrivateDiskCacheEnabled=true
  val random=Random(61)
  repeat(25){iteration->
   val original=Bitmap.createBitmap(9,8,Bitmap.Config.ARGB_8888)
   for(y in 0 until 8)for(x in 0 until 9){val v=100+random.nextInt(7);original.setPixel(x,y,Color.rgb(v,v,v))}
   val bytes=ByteArrayOutputStream().also{original.compress(Bitmap.CompressFormat.JPEG,100,it);original.recycle()}.toByteArray()
   val full=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888})!!
   assertTrue(PrivateScreenshotDiskCache.put(context,"audit",0,ScreenshotVariant.FULL,url,bytes,"private, max-age=60",Instant.now().toString(),false))
   val disk=PrivateScreenshotDiskCache.get(context,"audit",0,ScreenshotVariant.FULL,url)!!
   assertEquals("Same JPEG changed dHash after disk-cache decode on fixture $iteration",ImageHashHelper.computeDHash(full),ImageHashHelper.computeDHash(disk))
  }
 }
}
