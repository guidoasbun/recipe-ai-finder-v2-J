import Link from "next/link";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy Policy — Recipe AI Finder",
  description: "Learn how Recipe AI Finder collects, uses, and protects your personal data.",
};

const LAST_UPDATED = "2025-01-15";
const VERSION = "1.0";

export default function PrivacyPolicyPage() {
  return (
    <main className="flex flex-1 justify-center bg-gray-50 py-12 px-4">
      <article className="w-full max-w-3xl rounded-2xl bg-white p-8 shadow-md sm:p-12">
        <header className="mb-8 border-b border-gray-200 pb-6">
          <h1 className="text-3xl font-bold text-gray-900">Privacy Policy</h1>
          <p className="mt-2 text-sm text-gray-500">
            Last updated: <time dateTime={LAST_UPDATED}>{LAST_UPDATED}</time> &middot; Version {VERSION}
          </p>
        </header>

        <div className="space-y-8 text-sm leading-relaxed text-gray-700">
          {/* Introduction */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">1. Introduction</h2>
            <p>
              This Privacy Policy describes how Recipe AI Finder (&ldquo;we&rdquo;, &ldquo;us&rdquo;, or &ldquo;our&rdquo;)
              collects, uses, shares, and protects your personal data when you use our application. We are committed
              to compliance with the General Data Protection Regulation (GDPR), the California Consumer Privacy Act
              (CCPA/CPRA), and the Brazilian General Data Protection Law (LGPD).
            </p>
          </section>

          {/* Data Collected */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">2. Personal Data We Collect</h2>
            <p className="mb-3">We collect the following categories of personal data:</p>
            <ul className="list-disc space-y-2 pl-5">
              <li>
                <strong>Account Data:</strong> email address, username, and account creation date.
              </li>
              <li>
                <strong>User-Generated Content:</strong> recipe titles, descriptions, ingredients, and steps that you create.
              </li>
              <li>
                <strong>AI-Generated Content:</strong> recipe images generated on your behalf and associated model metadata (e.g., model used, generation time).
              </li>
              <li>
                <strong>Technical Data:</strong> IP address, user agent, and consent timestamps collected during your interactions with our service.
              </li>
            </ul>
          </section>

          {/* Purposes of Processing */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">3. Purposes of Processing</h2>
            <p className="mb-3">We process your personal data for the following purposes:</p>
            <ul className="list-disc space-y-2 pl-5">
              <li>
                <strong>Account Management:</strong> creating and maintaining your user account.
              </li>
              <li>
                <strong>AI-Powered Recipe Generation:</strong> generating recipe text and images using third-party AI processors (see Section 4 below).
              </li>
              <li>
                <strong>Recipe Image Generation and Storage:</strong> creating, storing, and serving AI-generated images associated with your recipes.
              </li>
              <li>
                <strong>Consent Record-Keeping:</strong> recording your consent decisions to demonstrate compliance with applicable regulations.
              </li>
              <li>
                <strong>Compliance Audit Logging:</strong> maintaining audit trails of compliance-relevant events for regulatory and incident investigation purposes.
              </li>
            </ul>
          </section>

          {/* Third-Party Processors */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">4. Third-Party Processors</h2>
            <p className="mb-3">
              To provide AI-powered recipe generation, we share relevant data with the following third-party processors:
            </p>
            <ul className="list-disc space-y-2 pl-5">
              <li><strong>AWS Bedrock</strong> &mdash; AI model hosting and inference</li>
              <li><strong>Stability AI</strong> &mdash; image generation</li>
              <li><strong>OpenAI</strong> &mdash; text and image generation</li>
              <li><strong>Google Imagen</strong> &mdash; image generation</li>
            </ul>
            <p className="mt-3">
              Data shared with these processors is limited to what is necessary to fulfill your recipe generation request.
              Each processor operates under its own privacy policy and data processing agreements.
            </p>
          </section>

          {/* Data Retention */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">5. Data Retention</h2>
            <p className="mb-3">We retain your personal data for the following periods:</p>
            <ul className="list-disc space-y-2 pl-5">
              <li>
                <strong>Recipe Images (S3):</strong> 90-day lifecycle. Images are automatically deleted 90 days after creation.
              </li>
              <li>
                <strong>Application Logs (CloudWatch):</strong> 30-day retention period.
              </li>
              <li>
                <strong>Account and Recipe Data:</strong> retained indefinitely until you initiate account deletion. Upon deletion, all associated data is permanently removed.
              </li>
            </ul>
          </section>

          {/* User Rights */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">6. Your Rights</h2>
            <p className="mb-3">
              Under applicable data protection regulations, you have the following rights:
            </p>
            <ul className="list-disc space-y-2 pl-5">
              <li>
                <strong>Right of Access:</strong> request a copy of all personal data we hold about you.
              </li>
              <li>
                <strong>Right to Rectification:</strong> request correction of inaccurate personal data.
              </li>
              <li>
                <strong>Right to Erasure:</strong> request deletion of your account and all associated data (account deletion).
              </li>
              <li>
                <strong>Right to Data Portability:</strong> export your data in machine-readable formats (JSON and ZIP).
              </li>
              <li>
                <strong>Right to Object:</strong> revoke consent for specific data processing activities (consent revocation).
              </li>
            </ul>
            <p className="mt-3">
              You can exercise these rights through your{" "}
              <Link href="/account" className="font-medium text-[#003DA5] hover:underline">
                Account Settings
              </Link>{" "}
              page or by contacting us using the details below.
            </p>
          </section>

          {/* Controller Contact */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">7. Data Controller</h2>
            <p className="mb-2">
              The data controller responsible for your personal data is:
            </p>
            <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
              <p className="font-medium text-gray-900">[Controller Name]</p>
              <p>[Address Line 1]</p>
              <p>[Address Line 2]</p>
              <p>Email: [controller@example.com]</p>
              <p>Phone: [+1-000-000-0000]</p>
            </div>
          </section>

          {/* DPO Contact */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">8. Data Protection Officer</h2>
            <p className="mb-2">
              For any questions or concerns regarding our data protection practices, you may contact our Data Protection Officer:
            </p>
            <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
              <p className="font-medium text-gray-900">[DPO Name]</p>
              <p>Email: [dpo@example.com]</p>
              <p>Phone: [+1-000-000-0000]</p>
            </div>
          </section>

          {/* Changes to Policy */}
          <section>
            <h2 className="mb-3 text-lg font-semibold text-gray-900">9. Changes to This Policy</h2>
            <p>
              We may update this Privacy Policy from time to time. When we make changes, we will update the
              &ldquo;Last updated&rdquo; date at the top of this page. We encourage you to review this policy
              periodically.
            </p>
          </section>
        </div>

        <footer className="mt-10 border-t border-gray-200 pt-6">
          <Link
            href="/"
            className="text-sm font-medium text-[#003DA5] hover:underline"
          >
            &larr; Back to home
          </Link>
        </footer>
      </article>
    </main>
  );
}
