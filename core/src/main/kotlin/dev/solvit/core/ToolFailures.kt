package dev.solvit.core

/** Host-owned descriptions only. Arbitrary exception messages and plugin error text are not diagnostics. */
enum class ToolFailureCode(val summary: String, val nextAction: String) {
    AUTHORIZATION_REVOKED("操作开始记录后授权被拒绝或撤销", "不会继续读取或执行；操作可能未开始或已产生影响，请先核对实际状态，再决定是否重新授权。"),
    INVALID_ARGUMENTS("工具参数无效", "检查工具声明的参数格式和范围，再按用户意图修正参数。"),
    FILE_NOT_FOUND("文件不存在", "先确认工作区和相对路径；需要新建时再请求写入。"),
    PATH_OUTSIDE_WORKSPACE("路径超出工作区", "只使用当前工作区内的相对路径，不要重试越界路径。"),
    NOT_REGULAR_FILE("目标不是普通文件", "先查看目录内容，选择文本文件；不要覆盖目录。"),
    FILE_TOO_LARGE("文件超过读取或写入限制", "缩小文件或分段处理，不要把超限当作文件不存在。"),
    WRITE_OUTCOME_UNKNOWN("写入后无法确认文件内容", "先读取实际文件并核对，不要自动重复写入。"),
    IO_ERROR("文件或网络读写失败", "检查实际文件或服务状态；操作可能已产生影响，重试前先核对。"),
    LOCAL_MCP_CONFIGURATION("本地MCP连接配置无效", "在MCP页面检查已保存的命令、参数JSON、环境变量和工作区相对目录；不要让模型临时补写启动命令。"),
    LOCAL_MCP_ENVIRONMENT("本地MCP运行环境未就绪", "先在Linux页面完成Ubuntu及所需Python/Node软件安装，再检查服务脚本和依赖是否已放入当前工作区。"),
    LOCAL_MCP_DISCONNECTED("本地MCP进程已结束或协议通信中断", "检查服务是否正常运行、stdout是否仅输出逐行JSON-RPC；工具可能已产生影响，先核对结果，再决定是否重新连接。"),
    RESOURCE_LIMIT("工具结果超过限制", "缩小查询范围或请求分页；不要重复执行可能有副作用的操作。"),
    TIMEOUT("工具执行超过时间限制", "操作可能已产生影响；先核对文件、任务或服务的实际状态，不要自动重复执行。"),
    UNKNOWN("工具执行失败", "检查插件状态、权限和参数；已开始的操作结果可能未知，先核对实际状态。"),
}
class ToolExecutionException(val code: ToolFailureCode) : IllegalArgumentException(code.summary)
fun ToolFailureCode.userDetails(): String = "错误代码：$name\n$summary\n下一步：$nextAction"
