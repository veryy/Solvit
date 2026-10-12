package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ToolFailureDetailsTest {
    @Test fun classifiedFailuresReachModelAndActivityWithoutArbitraryExceptionText()=runTest {
        val cases=listOf(
            ToolExecutionException(ToolFailureCode.FILE_NOT_FOUND) to ToolFailureCode.FILE_NOT_FOUND,
            IllegalArgumentException("secret-argument") to ToolFailureCode.INVALID_ARGUMENTS,
            java.io.IOException("secret-network") to ToolFailureCode.IO_ERROR,
            IllegalStateException("secret-runtime") to ToolFailureCode.UNKNOWN)
        for((failure,code) in cases) {
            val tools=ToolRegistry(EventBus()).apply { register(object : SolvitTool {
                override val descriptor=ToolDescriptor("read","read","{}")
                override suspend fun execute(argumentsJson: String): String=throw failure
            }) }
            var round=0
            val provider=object : ModelProvider {
                override val id="fixture"
                override fun stream(request: ModelRequest)=flow {
                    if(round++==0) emit(ModelChunk.ToolCalls(listOf(ToolCall("call","read","{}"))))
                    else {
                        val result=request.messages.last { it.role==Role.TOOL }
                        assertEquals(ToolStage.FAILED,result.toolStage);assertEquals(code.userDetails(),result.content)
                        assertFalse(result.content.contains("secret"));emit(ModelChunk.Text("checked failure"))
                    }
                    emit(ModelChunk.Done)
                }
            }
            val allow=object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor)=true }
            val events=ToolAgentRuntime(provider,tools).run(ModelRequest(ModelConfig(),listOf(ModelMessage(Role.USER,"read")),authorization=allow)).toList()
            assertEquals(code.userDetails(),events.filterIsInstance<ModelChunk.ToolActivity>().single { it.stage==ToolStage.FAILED }.result)
        }
    }
}
