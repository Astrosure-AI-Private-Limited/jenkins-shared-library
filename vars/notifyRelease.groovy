#!/usr/bin/env groovy
// AstroSure deploy notifier — posts to #release-{env} via the webhook in SSM.
// Usage:
//   @Library('astrosure-shared') _
//   notifyRelease(env:'dev', service:'payment-service', status:'success', ref: params.GIT_REF)

def call(Map args) {
  try {
    String envName = (args.env ?: 'dev')
    String service = (args.service ?: env.JOB_NAME)
    String status  = (args.status ?: 'started')
    String region  = (args.region ?: 'ap-south-1')
    String ref     = (args.ref ?: env.GIT_BRANCH ?: env.GIT_REF ?: '')

    String hook = sh(
        script: "aws ssm get-parameter --name /release/slack-webhook/${envName} --with-decryption --query Parameter.Value --output text --region ${region}",
        returnStdout: true
    ).trim()

    String author = ''; String sha = ''; String commits = ''
    try { author  = sh(script: "git log -1 --pretty='%an'",           returnStdout: true).trim() } catch (e) {}
    try { sha     = sh(script: "git rev-parse --short HEAD",          returnStdout: true).trim() } catch (e) {}
    try { commits = sh(script: "git log -5 --pretty='- %h %s (%an)'", returnStdout: true).trim() } catch (e) {}

    String triggeredBy = author
    try {
        def c = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
        if (c) { triggeredBy = c[0].userName ?: triggeredBy }
    } catch (e) {}

    Map look = [
        started: [emoji: ':hourglass_flowing_sand:', color: '#1976d2', word: 'started'],
        success: [emoji: ':rocket:',                 color: '#2e7d32', word: 'succeeded'],
        failed:  [emoji: ':x:',                      color: '#d32f2f', word: 'FAILED'],
    ]
    def s = look[status] ?: look.started
    String refLine = ref ? "   *Branch:* `${ref}`" : ""

    def payload = [ attachments: [[ color: s.color, blocks: [
        [type:'section', text:[type:'mrkdwn', text:"${s.emoji} *${envName.toUpperCase()}* deploy *${s.word}* — `${service}`  (build #${env.BUILD_NUMBER})"]],
        [type:'section', text:[type:'mrkdwn', text:"*By:* ${triggeredBy ?: 'n/a'}${refLine}   *Commit:* `${sha ?: 'n/a'}`"]],
        [type:'section', text:[type:'mrkdwn', text:"*Recent commits:*\n${commits ?: '_n/a_'}"]],
        [type:'context', elements:[[type:'mrkdwn', text:"<${env.BUILD_URL}|Open Jenkins build>   •   source: pipeline"]]],
    ]]]]

    writeFile file: 'slack_release_payload.json', text: groovy.json.JsonOutput.toJson(payload)
    sh "curl -sf -X POST -H 'Content-type: application/json' --data @slack_release_payload.json '${hook}'"
  } catch (err) {
    echo "notifyRelease skipped: ${err}"
  }
}
