package com.lattice.track;

public class StorageContext {
    public final String storageMod;
    public final String storageId;
    public final String actorType;
    public final String traceId;

    public StorageContext(String storageMod, String storageId, String actorType, String traceId) {
        this.storageMod = storageMod;
        this.storageId = storageId;
        this.actorType = actorType;
        this.traceId = traceId;
    }
}
