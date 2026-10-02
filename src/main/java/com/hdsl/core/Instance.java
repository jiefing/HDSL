package com.hdsl.core;

public record Instance(String id, String name, String runtimeVersion, String profile, int port, String customCommand, String accountId) {
    public Instance(String id,String name,String runtimeVersion,String profile,int port,String customCommand){this(id,name,runtimeVersion,profile,port,customCommand,"");}
    public Instance { accountId=accountId==null?"":accountId; }
}
