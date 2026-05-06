package com.datagami.rentaxis.core.email.send;

public record SendResult(String azureMessageId, String azureDeliveryStatus) {}
