package com.liminer.intake;

public interface RelevanceClassifier
{
    // fromDomain + subject + at most 500 chars of body — never the full email body.
    boolean isRelevant(String fromDomain, String subject, String bodyPrefix);
}
