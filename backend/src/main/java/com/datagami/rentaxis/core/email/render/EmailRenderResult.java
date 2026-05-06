package com.datagami.rentaxis.core.email.render;

public record EmailRenderResult(String subject, String html, String text, String attachmentsJson) {}
