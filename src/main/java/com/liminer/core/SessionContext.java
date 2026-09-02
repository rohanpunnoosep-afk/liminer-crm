package com.liminer.core;

public class SessionContext
{
    public UserAccount user;
    public CRMSchemaConfig config;

    public SessionContext(UserAccount user, CRMSchemaConfig config)
    {
        this.user = user;
        this.config = config;
    }

    public void printSummary()
    {
        System.out.println("===== SESSION CONTEXT =====");

        if (user != null)
        {
            user.printSummary();
        }

        if (config != null)
        {
            config.printSummary();
        }

        System.out.println("===========================");
    }
}