package se.bjurr.violations.comments.bitbucketserver.lib.client.model;

import java.util.List;

public class BitbucketServerComment {

  private final Integer id;
  private final String text;
  private final Integer version;
  private final List<BitbucketServerTask> tasks;
  private final List<BitbucketServerComment> subComments;

  public BitbucketServerComment() {
    this.id = null;
    this.text = null;
    this.version = null;
    this.tasks = null;
    this.subComments = null;
  }

  public BitbucketServerComment(Integer version, String text, Integer id, List<BitbucketServerTask> tasks, List<BitbucketServerComment> subComments) {
    this.version = version;
    this.text = text;
    this.id = id;
    this.tasks = tasks;
    this.subComments = subComments;
  }

  public Integer getId() {
    return this.id;
  }

  public String getText() {
    return this.text;
  }

  public Integer getVersion() {
    return this.version;
  }

  public List<BitbucketServerTask> getTasks() {
    return tasks;
  }

  public List<BitbucketServerComment> getSubComments() {
    return subComments;
  }
}
