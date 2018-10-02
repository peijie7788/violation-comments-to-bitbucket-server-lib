package se.bjurr.violations.comments.bitbucketserver.lib;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Suppliers.memoizeWithExpiration;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.SEVERE;
import static se.bjurr.violations.comments.bitbucketserver.lib.client.model.DIFFTYPE.ADDED;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import se.bjurr.violations.comments.bitbucketserver.lib.client.BitbucketServerClient;
import se.bjurr.violations.comments.bitbucketserver.lib.client.model.BitbucketServerComment;
import se.bjurr.violations.comments.bitbucketserver.lib.client.model.BitbucketServerDiff;
import se.bjurr.violations.comments.bitbucketserver.lib.client.model.BitbucketServerDiffResponse;
import se.bjurr.violations.comments.bitbucketserver.lib.client.model.BitbucketServerTask;
import se.bjurr.violations.comments.bitbucketserver.lib.client.model.DiffDestination;
import se.bjurr.violations.comments.bitbucketserver.lib.client.model.DiffHunk;
import se.bjurr.violations.comments.bitbucketserver.lib.client.model.Line;
import se.bjurr.violations.comments.bitbucketserver.lib.client.model.Segment;
import se.bjurr.violations.comments.lib.CommentsProvider;
import se.bjurr.violations.comments.lib.ViolationsLogger;
import se.bjurr.violations.comments.lib.model.ChangedFile;
import se.bjurr.violations.comments.lib.model.Comment;
import se.bjurr.violations.lib.util.Optional;

public class BitbucketServerCommentsProvider implements CommentsProvider {

  private final BitbucketServerClient client;

  private final Supplier<BitbucketServerDiffResponse> diffResponse =
      memoizeWithExpiration(
          new Supplier<BitbucketServerDiffResponse>() {
            @Override
            public BitbucketServerDiffResponse get() {
              return client.pullRequestDiff();
            }
          },
          10,
          SECONDS);

  private final ViolationCommentsToBitbucketServerApi violationCommentsToBitbucketApi;
  private final ViolationsLogger violationsLogger;

  @VisibleForTesting
  BitbucketServerCommentsProvider() {
    client = null;
    violationCommentsToBitbucketApi = null;
    violationsLogger = null;
  }

  public BitbucketServerCommentsProvider(
      final ViolationCommentsToBitbucketServerApi violationCommentsToBitbucketApi,
      final ViolationsLogger violationsLogger) {
    this.violationsLogger = violationsLogger;
    final String bitbucketServerBaseUrl = violationCommentsToBitbucketApi.getBitbucketServerUrl();
    final String bitbucketServerProject = violationCommentsToBitbucketApi.getProjectKey();
    final String bitbucketServerRepo = violationCommentsToBitbucketApi.getRepoSlug();
    final Integer bitbucketServerPullRequestId = violationCommentsToBitbucketApi.getPullRequestId();
    final String bitbucketServerUser = violationCommentsToBitbucketApi.getUsername();
    final String bitbucketServerPassword = violationCommentsToBitbucketApi.getPassword();
    final String bitbucketPersonalAccessToken =
        violationCommentsToBitbucketApi.getPersonalAccessToken();
    final String proxyHostNameOrIp = violationCommentsToBitbucketApi.getProxyHostNameOrIp();
    final Integer proxyHostPort = violationCommentsToBitbucketApi.getProxyHostPort();
    final String proxyUser = violationCommentsToBitbucketApi.getProxyUser();
    final String proxyPassword = violationCommentsToBitbucketApi.getProxyPassword();
    client =
        new BitbucketServerClient(
            violationsLogger,
            bitbucketServerBaseUrl,
            bitbucketServerProject,
            bitbucketServerRepo,
            bitbucketServerPullRequestId,
            bitbucketServerUser,
            bitbucketServerPassword,
            bitbucketPersonalAccessToken,
            proxyHostNameOrIp,
            proxyHostPort,
            proxyUser,
            proxyPassword);
    this.violationCommentsToBitbucketApi = violationCommentsToBitbucketApi;
  }

  @Override
  public void createCommentWithAllSingleFileComments(final String comment) {
    client.pullRequestComment(comment);
  }

  @Override
  public void createSingleFileComment(
      final ChangedFile file, final Integer line, final String comment) {
    final BitbucketServerComment bitbucketComment =
        client.pullRequestComment(file.getFilename(), line, comment);

    if (violationCommentsToBitbucketApi.getCreateSingleFileCommentsTasks()) {
      client.commentCreateTask(bitbucketComment, file.getFilename(), line);
    }
  }

  @Override
  public List<Comment> getComments() {
    final List<Comment> comments = newArrayList();
    for (final String changedFile : client.pullRequestChanges()) {
      final List<BitbucketServerComment> bitbucketServerCommentsOnFile =
          client.pullRequestComments(changedFile);
      for (final BitbucketServerComment fileComment : bitbucketServerCommentsOnFile) {
        final List<String> specifics = newArrayList(fileComment.getVersion() + "", changedFile);
        comments.add(new Comment(fileComment.getId() + "", fileComment.getText(), null, specifics));
      }
    }

    return comments;
  }

  @Override
  public List<ChangedFile> getFiles() {
    final List<ChangedFile> changedFiles = newArrayList();

    final List<String> bitbucketServerChangedFiles = client.pullRequestChanges();

    for (final String changedFile : bitbucketServerChangedFiles) {
      changedFiles.add(new ChangedFile(changedFile, new ArrayList<String>()));
    }

    return changedFiles;
  }

  @Override
  public void removeComments(final List<Comment> comments) {
    for (final Comment comment : comments) {
      Integer commentId = null;
      Integer commentVersion = null;
      try {
        commentId = Integer.valueOf(comment.getIdentifier());
        commentVersion = Integer.valueOf(comment.getSpecifics().get(0));

        BitbucketServerComment bitbucketServerComment = client.pullRequestComment((long) commentId);
        removeAllSubCommentsAndTasksOfComment(bitbucketServerComment);

        removeAllTasksOfComment(bitbucketServerComment);
        client.pullRequestRemoveComment(commentId, commentVersion);
      } catch (final Exception e) {
        violationsLogger.log(
            SEVERE, "Was unable to remove comment " + commentId + " " + commentVersion, e);
      }
    }
  }

  @Override
  public boolean shouldComment(final ChangedFile changedFile, final Integer changedLine) {
    if (!violationCommentsToBitbucketApi.getCommentOnlyChangedContent()) {
      return true;
    }
    final int context = violationCommentsToBitbucketApi.getCommentOnlyChangedContentContext();
    final List<BitbucketServerDiff> diffs = diffResponse.get().getDiffs();
    return shouldComment(changedFile, changedLine, context, diffs);
  }

  @VisibleForTesting
  boolean shouldComment(
      final ChangedFile changedFile,
      final Integer changedLine,
      final int context,
      final List<BitbucketServerDiff> diffs) {
    for (final BitbucketServerDiff diff : diffs) {
      final DiffDestination destination = diff.getDestination();
      if (destination != null) {
        final String destinationToString = destination.getToString();
        if (!isNullOrEmpty(destinationToString)) {
          if (destinationToString.equals(changedFile.getFilename())) {
            if (diff.getHunks() != null) {
              for (final DiffHunk hunk : diff.getHunks()) {
                for (final Segment segment : hunk.getSegments()) {
                  if (segment.getType() == ADDED) {
                    for (final Line line : segment.getLines()) {
                      if (line.getDestination() >= changedLine - context
                          && line.getDestination() <= changedLine + context) {
                        return true;
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    return false;
  }

  @Override
  public boolean shouldCreateCommentWithAllSingleFileComments() {
    return violationCommentsToBitbucketApi.getCreateCommentWithAllSingleFileComments();
  }

  @Override
  public boolean shouldCreateSingleFileComment() {
    return violationCommentsToBitbucketApi.getCreateSingleFileComments();
  }

  @Override
  public Optional<String> findCommentTemplate() {
    return violationCommentsToBitbucketApi.findCommentTemplate();
  }

  @Override
  public boolean shouldKeepOldComments() {
    return violationCommentsToBitbucketApi.getShouldKeepOldComments();
  }

  private void removeAllTasksOfComment(final BitbucketServerComment bitbucketServerComment) {
    List<BitbucketServerTask> bitbucketServerTasks = bitbucketServerComment.getTasks();

    for (BitbucketServerTask bitbucketServerTask : bitbucketServerTasks) {
      client.removeTask(bitbucketServerTask);
    }
  }

  private void removeAllSubCommentsAndTasksOfComment(
      final BitbucketServerComment bitbucketServerComment) {
    final Deque<BitbucketServerComment> commentStack = new ArrayDeque<>();
    Collection<BitbucketServerComment> subComments = bitbucketServerComment.getComments();
    while (subComments != null && !subComments.isEmpty()) {
      commentStack.addAll(subComments);
      final Collection<BitbucketServerComment> currentSubComments = subComments;

      subComments = new ArrayList<>();
      for (final BitbucketServerComment subComment : currentSubComments) {
        subComments.addAll(subComment.getComments());
      }
    }

    final Iterator<BitbucketServerComment> commentIt = commentStack.descendingIterator();
    while (commentIt.hasNext()) {
      final BitbucketServerComment comment = commentIt.next();
      removeAllTasksOfComment(comment);
      client.pullRequestRemoveComment(comment.getId(), comment.getVersion());
    }
  }
}
