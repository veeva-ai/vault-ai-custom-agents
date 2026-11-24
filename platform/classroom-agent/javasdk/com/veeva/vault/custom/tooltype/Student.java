package com.veeva.vault.custom.tooltype;

import com.veeva.vault.sdk.api.core.UserDefinedClassInfo;

/**
 * An active Student ({@code person__sys} record of object type {@code student__c}) as read by
 * {@link ClassroomLookups}: its record id and display name.
 */
@UserDefinedClassInfo
public class Student {

    private final String id;
    private final String name;

    public Student(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
