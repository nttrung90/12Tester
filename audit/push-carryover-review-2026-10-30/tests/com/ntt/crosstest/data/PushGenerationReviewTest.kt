package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class PushGenerationReviewTest {
 @OptIn(ExperimentalCoroutinesApi::class)
 @Test fun purchaseCallbackCannotPublishAfterSameUserStartsNewSession() = runBlocking {
  val context:Application=RuntimeEnvironment.getApplication()
  SessionCacheManager.clear(context)
  Dispatchers.setMain(UnconfinedTestDispatcher())
  val entered=CompletableDeferred<Unit>()
  val release=CompletableDeferred<Unit>()
  var callbacks=0
  val c=createSupabaseClient("https://fixture.invalid","dummy-key") {
   install(Auth){sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache();autoLoadFromStorage=false;autoSaveToStorage=false}
   install(Postgrest)
   httpEngine=MockEngine { req ->
    val body=when {
     req.url.toString().contains("get_campaign_detail") -> """{"campaign":{"id":"cA","owner_id":"userA","app_name":"A","status":"open","slot_number":1},"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":250}}"""
     req.url.toString().contains("get_campaign_mission") -> """{"mission":null}"""
     req.url.toString().contains("push_campaign") -> {
      entered.complete(Unit);release.await()
      """{"success":true,"receipt_id":"rA","client_request_id":"reqA","campaign_id":"cA","charged_credits":100,"balance_after":150,"business_date":"2026-10-30","expires_at":"2026-10-30T17:00:00Z","push_order":1,"replayed":false}"""
     }
     else -> "[]"
    }
    respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
   }
  }
  val json=Json {ignoreUnknownKeys=true}
  suspend fun login(uid:String){c.auth.importSession(json.decodeFromString<UserSession>("""{"access_token":"token-$uid","refresh_token":"refresh-$uid","token_type":"bearer","expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}"""))}
  login("userA")
  CampaignPushStore.clearPendingRequestId(context,"userA","cA")
  val vm=OwnerAppDetailViewModel("cA",CampaignRepository(c,Localizer(context)),Localizer(context))
  try {
   withTimeout(5000){vm.uiState.first{it.campaign!=null && !it.isBackgroundSyncing}}
   vm.pushApp(context){callbacks++}
   withTimeout(5000){entered.await()}
   SessionCacheManager.clear(context)
   login("userB");login("userA")
   release.complete(Unit)
   withTimeout(5000){vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()}
   assertEquals("Old generation must not publish a success callback even with same uid",0,callbacks)
  } finally {
   vm.viewModelScope.cancel()
   CampaignPushStore.clearPendingRequestId(context,"userA","cA")
   SessionCacheManager.clear(context)
   Dispatchers.resetMain()
  }
 }
}
