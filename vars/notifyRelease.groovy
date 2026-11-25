#!/usr/bin/env groovy
// AstroSure deploy notifier — posts the pro "release card" via the central
// Function URL (same card as the GitHub Actions deploys). Token in SSM.
// Usage:
//   @Library('astrosure-shared') _
//   post {
//     success { notifyRelease(env:'prod', service:'astrosure-backend', status:'success', appUrl:'https://app.astrosure.ai') }
//     failure { notifyRelease(env:'prod', service:'astrosure-backend', status:'failed',  appUrl:'https://app.astrosure.ai') }
//   }
def call(Map args) {
  try {
    String envName = (args.env ?: 'dev')
    String service = (args.service ?: env.JOB_NAME)
    String status  = (args.status ?: 'started')
    String region  = (args.region ?: 'ap-south-1')
    String appUrl  = (args.appUrl ?: '')
    String NOTIFY_URL = 'https://lg2wwdrgnranhmrvbwjaghkm240avyzu.lambda-url.ap-south-1.on.aws/'

    String st = (status == 'success') ? 'success' : ((status in ['failed','failure']) ? 'failure' : 'started')
    String token = sh(script: "aws ssm get-parameter --name /release/notify-token --with-decryption --query Parameter.Value --output text --region ${region}", returnStdout: true).trim()

    String author=''; String sha=''; String prev=''; String repo=''
    try { author = sh(script: "git log -1 --pretty='%an'", returnStdout: true).trim() } catch(e){}
    try { sha    = sh(script: "git rev-parse HEAD",         returnStdout: true).trim() } catch(e){}
    try { prev   = sh(script: "git rev-parse HEAD~1",       returnStdout: true).trim() } catch(e){}
    try { repo   = sh(script: "git config --get remote.origin.url", returnStdout: true).trim().replaceAll('.*github.com[:/]','').replaceAll('\\.git$','') } catch(e){}

    String triggeredBy = author
    try { def c = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause'); if (c) triggeredBy = c[0].userName ?: triggeredBy } catch(e){}

    def commits = []
    try {
      String raw = sh(script: "git log -5 --pretty='%H|~|%s|~|%an'", returnStdout: true).trim()
      raw.split('\n').each { line -> def p = line.split(/\|~\|/); if (p.length>=3) commits << [msg:p[1], author:p[2], sha:p[0]] }
    } catch(e){}

    String when = new Date().format('dd MMM yyyy, h:mm a', TimeZone.getTimeZone('Asia/Kolkata')) + ' IST'

    def payload = [
      token: token, env: envName, service: service, cluster: (args.cluster ?: ''), status: st,
      actor: (triggeredBy ?: 'Jenkins'), source: 'Jenkins',
      pipeline_ref: "build #${env.BUILD_NUMBER}", build_url: env.BUILD_URL,
      version: (sha ? sha.take(7) : ''), sha: sha, prev_sha: prev, repo: repo,
      when: when, app_url: appUrl, commits: commits
    ]
    writeFile file: 'release_payload.json', text: groovy.json.JsonOutput.toJson(payload)
    sh "curl -s -X POST -H 'Content-Type: application/json' --data @release_payload.json '${NOTIFY_URL}' || true"
  } catch (err) {
    echo "notifyRelease skipped: ${err}"
  }
}
