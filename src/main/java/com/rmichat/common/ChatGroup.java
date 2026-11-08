package com.rmichat.common;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Đại diện cho một Nhóm Chat (Group Chat) trong hệ thống RMI Chat.
 * Bắt buộc implement Serializable để truyền nhận qua mạng giữa Client và Server cũng như giữa 2 Server.
 */
public class ChatGroup implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String groupId;
    private String groupName;
    private final String creator;
    private final Set<String> members = new ConcurrentSkipListSet<>();
    private final long createdAt;

    public ChatGroup(String groupName, String creator) {
        this(UUID.randomUUID().toString(), groupName, creator, System.currentTimeMillis());
    }

    public ChatGroup(String groupId, String groupName, String creator, long createdAt) {
        this.groupId = groupId != null ? groupId : UUID.randomUUID().toString();
        this.groupName = groupName;
        this.creator = creator;
        this.createdAt = createdAt;
        if (creator != null && !creator.trim().isEmpty()) {
            this.members.add(creator.trim());
        }
    }

    public ChatGroup(String groupId, String groupName, String creator, Collection<String> initialMembers, long createdAt) {
        this.groupId = groupId != null ? groupId : UUID.randomUUID().toString();
        this.groupName = groupName;
        this.creator = creator;
        this.createdAt = createdAt;
        if (creator != null && !creator.trim().isEmpty()) {
            this.members.add(creator.trim());
        }
        if (initialMembers != null) {
            for (String m : initialMembers) {
                if (m != null && !m.trim().isEmpty()) {
                    this.members.add(m.trim());
                }
            }
        }
    }

    public String getGroupId() {
        return groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getCreator() {
        return creator;
    }

    public Set<String> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    public boolean addMember(String username) {
        if (username != null && !username.trim().isEmpty()) {
            return members.add(username.trim());
        }
        return false;
    }

    public boolean removeMember(String username) {
        if (username != null) {
            return members.remove(username.trim());
        }
        return false;
    }

    public boolean isMember(String username) {
        return username != null && members.contains(username.trim());
    }

    public int getMemberCount() {
        return members.size();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatGroup chatGroup = (ChatGroup) o;
        return Objects.equals(groupId, chatGroup.groupId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId);
    }

    @Override
    public String toString() {
        return String.format("ChatGroup{id='%s', name='%s', members=%d}", groupId, groupName, members.size());
    }
}
