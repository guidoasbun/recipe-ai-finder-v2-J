import Header from "@/components/layout/Header";
import Footer from "@/components/layout/Footer";
import ConsentGate from "@/components/layout/ConsentGate";
import PendingDeletionBanner from "@/components/layout/PendingDeletionBanner";
import { getSession } from "@/lib/session";
import { fetchProfile } from "@/lib/compliance-api";

export default async function ProtectedLayout({ children }: { children: React.ReactNode }) {
  let accountStatus: string | null = null;
  let scheduledDeletionDate: string | null = null;

  try {
    const session = await getSession();
    if (session) {
      const profile = await fetchProfile(session);
      accountStatus = profile.accountStatus;
      scheduledDeletionDate = profile.scheduledDeletionDate;
    }
  } catch {
    // If profile fetch fails, don't block rendering
  }

  return (
    <div className="flex min-h-screen flex-col bg-gray-50">
      <Header />
      {accountStatus === "PENDING_DELETION" && scheduledDeletionDate && (
        <PendingDeletionBanner scheduledDeletionDate={scheduledDeletionDate} />
      )}
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
        <ConsentGate>{children}</ConsentGate>
      </main>
      <Footer />
    </div>
  );
}
