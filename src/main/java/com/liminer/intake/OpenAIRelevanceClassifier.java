package com.liminer.intake;

import com.liminer.llm.OpenAIClient;

public class OpenAIRelevanceClassifier implements RelevanceClassifier
{
    @Override
    public boolean isRelevant(String fromDomain0, String subject0, String bodyPrefix0)
    {
        String prompt0 =
            "You are screening a single email for a venture capital fundraising CRM.\n"
            + "Decide whether this email is related to a fundraising / LP (limited partner) "
            + "relationship (e.g. an investor inquiry, due diligence, capital commitment, "
            + "portfolio update, or fund manager correspondence).\n\n"
            + "Sender domain: " + fromDomain0 + "\n"
            + "Subject: " + subject0 + "\n"
            + "Body excerpt: " + bodyPrefix0 + "\n\n"
            + "Answer with exactly one word: yes or no.";

        try
        {
            String response0 = OpenAIClient.getTextResponse(prompt0);

            if (response0 == null)
            {
                return false;
            }

            return response0.trim().toLowerCase().startsWith("yes");
        }
        catch (Exception exception0)
        {
            return false;
        }
    }
}
