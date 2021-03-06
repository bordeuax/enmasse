/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.controller.router.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LinkRoute {
    private String name;
    private String prefix;
    private String pattern;
    private String delExternalPrefix;
    private LinkDirection direction;
    private String containerId;
    private String connection;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public LinkDirection getDirection() {
        return direction;
    }

    public void setDirection(LinkDirection direction) {
        this.direction = direction;
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public String getConnection() {
        return connection;
    }

    public void setConnection(String connection) {
        this.connection = connection;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getDelExternalPrefix() {
        return delExternalPrefix;
    }

    public void setDelExternalPrefix(String delExternalPrefix) {
        this.delExternalPrefix = delExternalPrefix;
    }

    @Override
    public String toString() {
        return "LinkRoute{" +
                "name='" + name + '\'' +
                ", prefix='" + prefix + '\'' +
                ", pattern='" + pattern + '\'' +
                ", delExternalPrefix='" + delExternalPrefix + '\'' +
                ", direction=" + direction +
                ", containerId='" + containerId + '\'' +
                ", connection='" + connection + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LinkRoute linkRoute = (LinkRoute) o;
        return Objects.equals(name, linkRoute.name) &&
                Objects.equals(prefix, linkRoute.prefix) &&
                Objects.equals(pattern, linkRoute.pattern) &&
                Objects.equals(delExternalPrefix, linkRoute.delExternalPrefix) &&
                direction == linkRoute.direction &&
                Objects.equals(containerId, linkRoute.containerId) &&
                Objects.equals(connection, linkRoute.connection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, prefix, pattern, delExternalPrefix, direction, containerId, connection);
    }

}
