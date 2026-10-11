package dev.solvit.app

import android.app.Application
import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ToolApprovalStoreTest {
    @Test fun grantsSurviveRecreationButNeverCrossRoleCodePermissionsOrExecutionMode() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val first = ToolApprovalStore(context); first.clear()
        val tool = ToolDescriptor("write", "Write", "{}", pluginId="test.files", pluginVersion="1.0", pluginDigest="a", executionMode=ExecutionMode.APP)
        val role = AgentRole("coder","Coder", "Write requested files", pluginIds=setOf("test.files"))
        first.set(tool,role,ToolPolicy.ALLOW)
        val restored = ToolApprovalStore(context)
        assertEquals(ToolPolicy.ALLOW,restored.policy(tool,role))
        assertEquals(ToolPolicy.ASK,restored.policy(tool,null))
        assertEquals(ToolPolicy.ASK,restored.policy(tool,role.copy(prompt="Different intent")))
        assertEquals(ToolPolicy.ASK,restored.policy(tool.copy(pluginDigest="b"),role))
        assertEquals(ToolPolicy.ASK,restored.policy(tool.copy(executionMode=ExecutionMode.ROOT),role))
        assertEquals(ToolPolicy.ASK,restored.policy(tool.copy(androidPermissions=setOf("camera")),role))
        restored.clear()
        assertEquals(ToolPolicy.ASK,ToolApprovalStore(context).policy(tool,role))
    }
}
