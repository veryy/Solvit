package dev.solvit.git;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.ProcessResult;
import java.io.OutputStream;

/** Git operations never execute workspace-provided hooks or arbitrary shell commands. */
public final class WorkspaceGitFileSystem extends FS_POSIX {
    public WorkspaceGitFileSystem() { super(); }
    @Override public FS newInstance() { return new WorkspaceGitFileSystem(); }
    @Override public ProcessResult runHookIfPresent(Repository repository, String hookName,
            String[] args, OutputStream output, OutputStream error, String input) {
        return new ProcessResult(ProcessResult.Status.NOT_PRESENT);
    }
    @Override public ProcessBuilder runInShell(String command, String[] args) {
        throw new IllegalStateException("Git shell execution is unavailable; use the authorized Shell plugin");
    }
}
