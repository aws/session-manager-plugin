FROM golang:1.20

RUN apt -y update && apt -y upgrade && apt -y install rpm tar gzip wget zip && apt clean all

RUN mkdir /session-manager-plugin
WORKDIR /session-manager-plugin
